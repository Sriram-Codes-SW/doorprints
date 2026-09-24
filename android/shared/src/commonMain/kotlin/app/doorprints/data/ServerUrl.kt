package app.doorprints.data

/**
 * Validates the server URL typed in Settings (threat model F-02, SEC-004): HTTPS is required, except for the
 * local development hosts that res/xml/network_security_config.xml also allows in cleartext.
 *
 * Common code since CMP-4 P4b (ADR-23; was `:app`, where it parsed with `java.net.URI`). [UriParts] is a port of the
 * part of `java.net.URI`'s parser (OpenJDK 21, `URI.Parser`, RFC 2396 with its RFC 2732 IPv6 deviation) that this
 * check reads, so every string gives the same answer as before: `ServerUrlTest` (commonTest) holds the cases and
 * `ServerUrlParityTest` (androidHostTest) compares the port with the JVM's `java.net.URI` on generated strings.
 * On a phone the app used Android's `java.net.URI` (libcore), which also allows `_` inside a host name label
 * ("Android-changed: Allow underscore in hostname", since Android 5); [check] keeps that, so `https://my_api.example`
 * is accepted as before on the phone.
 */
object ServerUrl {
    val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    sealed interface Result {
        data class Ok(val url: String) : Result
        data object Empty : Result
        data object Invalid : Result
        data object NotHttps : Result
    }

    fun check(raw: String): Result = check(raw, underscoreInHostnames = true)

    /** [check] with the JVM's host name rule when [underscoreInHostnames] is false (for `ServerUrlParityTest`). */
    internal fun check(raw: String, underscoreInHostnames: Boolean): Result {
        val text = raw.trim().trimEnd('/')
        if (text.isEmpty()) return Result.Empty
        val uri = UriParts.parse(text, underscoreInHostnames) ?: return Result.Invalid
        val scheme = uri.scheme?.lowercase() ?: return Result.Invalid
        val host = uri.host?.lowercase() ?: return Result.Invalid
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return Result.Invalid
        return when (scheme) {
            "https" -> Result.Ok(text)
            "http" -> if (host in LOCAL_HOSTS) Result.Ok(text) else Result.NotHttps
            else -> Result.Invalid
        }
    }
}

/**
 * The components of a URI reference that [ServerUrl] reads, as `java.net.URI(String)` sets them: `getScheme()`,
 * `getRawUserInfo()`, `getHost()` (with the brackets of an IPv6 literal), `getRawQuery()` and `getRawFragment()`.
 * [parse] returns null where `java.net.URI` throws `URISyntaxException`.
 *
 * A line-by-line port of OpenJDK 21's `URI.Parser.parse(false)`, keeping its names, masks and order of checks; the
 * path and port are checked but not kept. Characters are UTF-16 units as in Java. `Character.isSpaceChar` is the
 * Unicode categories Zs, Zl and Zp, and `Character.isISOControl` U+0000..U+001F and U+007F..U+009F.
 */
internal class UriParts private constructor(
    val scheme: String?,
    val userInfo: String?,
    val host: String?,
    val query: String?,
    val fragment: String?,
) {
    companion object {
        /**
         * [underscoreInHostnames]: true parses as Android's libcore does (a `_` may follow a label's first char),
         * false as OpenJDK does. That is the only difference between the two for these components.
         */
        fun parse(input: String, underscoreInHostnames: Boolean): UriParts? = try {
            Parser(input, underscoreInHostnames).parse()
        } catch (_: UriSyntaxException) {
            null
        }
    }

    private class UriSyntaxException : Exception()

    private class Parser(private val input: String, underscoreInHostnames: Boolean) {
        private val labelLow = L_ALPHANUM or L_DASH or (if (underscoreInHostnames) L_UNDERSCORE else 0L)
        private val labelHigh = H_ALPHANUM or H_DASH or (if (underscoreInHostnames) H_UNDERSCORE else 0L)

        private var scheme: String? = null
        private var userInfo: String? = null
        private var host: String? = null
        private var query: String? = null
        private var fragment: String? = null
        private var ipv6byteCount = 0

        private fun fail(): Nothing = throw UriSyntaxException()

        private fun at(start: Int, end: Int, c: Char) = start < end && input[start] == c

        private fun at(start: Int, end: Int, s: String): Boolean {
            if (s.length > end - start) return false
            return input.regionMatches(start, s, 0, s.length)
        }

        /** The char at [start] if it is [c]: the index after it, else [start]. */
        private fun scan(start: Int, end: Int, c: Char) = if (start < end && input[start] == c) start + 1 else start

        /** Up to the first char in [stop] (its index) or [end]; -1 at the first char in [err]. */
        private fun scan(start: Int, end: Int, err: String, stop: String): Int {
            var p = start
            while (p < end) {
                val c = input[p]
                if (err.indexOf(c) >= 0) return -1
                if (stop.indexOf(c) >= 0) break
                p++
            }
            return p
        }

        private fun scan(start: Int, end: Int, stop: String) = scan(start, end, "", stop)

        private fun scanEscape(start: Int, n: Int, c: Char): Int {
            if (c == '%') {
                if (start + 3 <= n && match(input[start + 1], L_HEX, H_HEX) && match(input[start + 2], L_HEX, H_HEX)) {
                    return start + 3
                }
                fail()
            } else if (c.code > 128 && !isSpaceChar(c) && !isIsoControl(c)) {
                return start + 1 // visible non-US-ASCII chars are allowed unescaped
            }
            return start
        }

        private fun scan(start: Int, n: Int, lowMask: Long, highMask: Long): Int {
            var p = start
            while (p < n) {
                val c = input[p]
                if (match(c, lowMask, highMask)) {
                    p++
                    continue
                }
                if (lowMask and L_ESCAPED != 0L) {
                    val q = scanEscape(p, n, c)
                    if (q > p) {
                        p = q
                        continue
                    }
                }
                break
            }
            return p
        }

        private fun checkChars(start: Int, end: Int, lowMask: Long, highMask: Long) {
            if (scan(start, end, lowMask, highMask) < end) fail()
        }

        private fun checkChar(p: Int, lowMask: Long, highMask: Long) = checkChars(p, p + 1, lowMask, highMask)

        // [<scheme>:]<scheme-specific-part>[#<fragment>]
        fun parse(): UriParts {
            val n = input.length
            var p = scan(0, n, "/?#", ":")
            if (p >= 0 && at(p, n, ':')) {
                if (p == 0) fail()
                checkChar(0, L_ALPHA, H_ALPHA)
                checkChars(1, p, L_SCHEME, H_SCHEME)
                scheme = input.substring(0, p)
                p++ // skip ':'
                if (at(p, n, '/')) {
                    p = parseHierarchical(p, n)
                } else {
                    // opaque
                    val q = scan(p, n, "#")
                    if (q <= p) fail()
                    checkChars(p, q, L_URIC, H_URIC)
                    p = q
                }
            } else {
                p = parseHierarchical(0, n)
            }
            if (at(p, n, '#')) {
                checkChars(p + 1, n, L_URIC, H_URIC)
                fragment = input.substring(p + 1, n)
                p = n
            }
            if (p < n) fail()
            return UriParts(scheme, userInfo, host, query, fragment)
        }

        // [//authority]<path>[?<query>]; an empty authority is allowed before a path, query or fragment.
        private fun parseHierarchical(start: Int, n: Int): Int {
            var p = start
            if (at(p, n, '/') && at(p + 1, n, '/')) {
                p += 2
                val q = scan(p, n, "/?#")
                if (q > p) {
                    p = parseAuthority(p, q)
                } else if (q >= n) {
                    fail()
                }
            }
            var q = scan(p, n, "?#")
            checkChars(p, q, L_PATH, H_PATH)
            p = q
            if (at(p, n, '?')) {
                p++
                q = scan(p, n, "#")
                checkChars(p, q, L_URIC, H_URIC)
                query = input.substring(p, q)
                p = q
            }
            return p
        }

        // authority = server | reg_name. Always called with requireServerAuthority = false: an authority that does
        // not parse as a server is registry-based (no user info, host or port) when its chars allow that.
        private fun parseAuthority(start: Int, n: Int): Int {
            val p = start
            var q = p
            val serverChars = if (scan(p, n, "]") > p) {
                scan(p, n, L_SERVER_PERCENT, H_SERVER_PERCENT) == n
            } else {
                scan(p, n, L_SERVER, H_SERVER) == n
            }
            val regChars = scan(p, n, L_REG_NAME, H_REG_NAME) == n
            if (regChars && !serverChars) return n
            val skipParseException = regChars
            if (serverChars) {
                try {
                    q = parseServer(p, n, skipParseException)
                    if (q < n) {
                        if (!skipParseException) fail()
                        undoServer()
                        q = p
                    }
                } catch (_: UriSyntaxException) {
                    undoServer()
                    q = p
                }
            }
            if (q < n && !regChars) fail()
            return n
        }

        private fun undoServer() {
            userInfo = null
            host = null
        }

        // [<userinfo>@]<host>[:<port>]
        private fun parseServer(start: Int, n: Int, skipParseException: Boolean): Int {
            var p = start
            var q = scan(p, n, "/?#", "@")
            if (q >= p && at(q, n, '@')) {
                checkChars(p, q, L_USERINFO, H_USERINFO)
                userInfo = input.substring(p, q)
                p = q + 1
            }
            if (at(p, n, '[')) {
                p++
                q = scan(p, n, "/?#", "]")
                if (q > p && at(q, n, ']')) {
                    val r = scan(p, q, "%")
                    if (r > p) {
                        parseIPv6Reference(p, r)
                        if (r + 1 == q) fail()
                        checkChars(r + 1, q, L_SCOPE_ID, H_SCOPE_ID)
                    } else {
                        parseIPv6Reference(p, q)
                    }
                    host = input.substring(p - 1, q + 1)
                    p = q + 1
                } else {
                    fail()
                }
            } else {
                q = parseIPv4Address(p, n)
                if (q <= p) q = parseHostname(p, n, skipParseException)
                p = q
            }
            if (at(p, n, ':')) {
                p++
                q = scan(p, n, "/")
                if (q > p) {
                    checkChars(p, q, L_DIGIT, H_DIGIT)
                    input.substring(p, q).toIntOrNull() ?: fail() // Integer.parseInt: digits that fit an Int
                    p = q
                }
            } else if (p < n && skipParseException) {
                return p
            }
            if (p < n) fail()
            return p
        }

        private fun scanByte(start: Int, n: Int): Int {
            val p = start
            val q = scan(p, n, L_DIGIT, H_DIGIT)
            if (q <= p) return q
            var i = p
            while (true) {
                val j = scan(i, q, '0')
                if (j > i) i = j else break
            }
            val significantDigits = q - i
            if (significantDigits < 3) return q
            if (significantDigits > 3) return p
            if (input.substring(p, q).toInt() > 255) return p
            return q
        }

        private fun scanIPv4Address(start: Int, n: Int, strict: Boolean): Int {
            var p = start
            var q = 0
            val m = scan(p, n, L_DIGIT or L_DOT, H_DIGIT or H_DOT)
            if (m <= p || (strict && m != n)) return -1
            run {
                q = scanByte(p, m); if (q <= p) return@run; p = q
                q = scan(p, m, '.'); if (q <= p) return@run; p = q
                q = scanByte(p, m); if (q <= p) return@run; p = q
                q = scan(p, m, '.'); if (q <= p) return@run; p = q
                q = scanByte(p, m); if (q <= p) return@run; p = q
                q = scan(p, m, '.'); if (q <= p) return@run; p = q
                q = scanByte(p, m); if (q <= p) return@run; p = q
                if (q < m) return@run
                return q
            }
            if (strict) fail()
            return -1
        }

        private fun takeIPv4Address(start: Int, n: Int): Int {
            val p = scanIPv4Address(start, n, true)
            if (p <= start) fail()
            return p
        }

        private fun parseIPv4Address(start: Int, n: Int): Int {
            val p = try {
                scanIPv4Address(start, n, false)
            } catch (_: UriSyntaxException) {
                return -1
            }
            if (p == -1) return p
            if (p > start && p < n && input[p] != ':') return -1
            if (p > start) host = input.substring(start, p)
            return p
        }

        // hostname = domainlabel [ "." ] | 1*( domainlabel "." ) toplabel [ "." ]
        private fun parseHostname(start: Int, n: Int, skipParseException: Boolean): Int {
            var p = start
            var q: Int
            var l = -1 // start of the last label
            do {
                q = scan(p, n, L_ALPHANUM, H_ALPHANUM)
                if (q <= p) break
                l = p
                p = q
                q = scan(p, n, labelLow, labelHigh)
                if (q > p) {
                    if (input[q - 1] == '-') fail()
                    p = q
                }
                q = scan(p, n, '.')
                if (q <= p) break
                p = q
            } while (p < n)
            if (p < n && !at(p, n, ':')) {
                if (skipParseException) return p
                fail()
            }
            if (l < 0) fail()
            // A fully qualified hostname's rightmost label starts with a letter.
            if (l > start && !match(input[l], L_ALPHA, H_ALPHA)) fail()
            host = input.substring(start, p)
            return p
        }

        // IPv6 (RFC 2373, with java.net.URI's revised grammar and its 16-byte limits).
        private fun parseIPv6Reference(start: Int, n: Int): Int {
            var p = start
            var compressedZeros = false
            val q = scanHexSeq(p, n)
            if (q > p) {
                p = q
                if (at(p, n, "::")) {
                    compressedZeros = true
                    p = scanHexPost(p + 2, n)
                } else if (at(p, n, ':')) {
                    p = takeIPv4Address(p + 1, n)
                    ipv6byteCount += 4
                }
            } else if (at(p, n, "::")) {
                compressedZeros = true
                p = scanHexPost(p + 2, n)
            }
            if (p < n) fail()
            if (ipv6byteCount > 16) fail()
            if (!compressedZeros && ipv6byteCount < 16) fail()
            if (compressedZeros && ipv6byteCount == 16) fail()
            return p
        }

        private fun scanHexPost(start: Int, n: Int): Int {
            var p = start
            if (p == n) return p
            val q = scanHexSeq(p, n)
            if (q > p) {
                p = q
                if (at(p, n, ':')) {
                    p++
                    p = takeIPv4Address(p, n)
                    ipv6byteCount += 4
                }
            } else {
                p = takeIPv4Address(p, n)
                ipv6byteCount += 4
            }
            return p
        }

        private fun scanHexSeq(start: Int, n: Int): Int {
            var p = start
            var q = scan(p, n, L_HEX, H_HEX)
            if (q <= p) return -1
            if (at(q, n, '.')) return -1 // the start of an IPv4 address
            if (q > p + 4) fail()
            ipv6byteCount += 2
            p = q
            while (p < n) {
                if (!at(p, n, ':')) break
                if (at(p + 1, n, ':')) break // "::"
                p++
                q = scan(p, n, L_HEX, H_HEX)
                if (q <= p) fail()
                if (at(q, n, '.')) {
                    p--
                    break
                }
                if (q > p + 4) fail()
                ipv6byteCount += 2
                p = q
            }
            return p
        }
    }
}

// java.net.URI's character classes: bit c of the low mask for chars 1..63, bit c - 64 of the high mask for 64..127.
// The values are OpenJDK's (URI.java), with its derivations.

private fun match(c: Char, lowMask: Long, highMask: Long): Boolean {
    val code = c.code
    if (code == 0) return false
    if (code < 64) return (1L shl code) and lowMask != 0L
    if (code < 128) return (1L shl (code - 64)) and highMask != 0L
    return false
}

/** `Character.isSpaceChar`: Unicode space, line and paragraph separators. */
private fun isSpaceChar(c: Char) = when (c.category) {
    CharCategory.SPACE_SEPARATOR, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR -> true
    else -> false
}

/** `Character.isISOControl`. */
private fun isIsoControl(c: Char) = c.code <= 0x1F || c.code in 0x7F..0x9F

private const val L_DIGIT = 0x3FF000000000000L
private const val H_DIGIT = 0L
private const val L_UPALPHA = 0L
private const val H_UPALPHA = 0x7FFFFFEL
private const val L_LOWALPHA = 0L
private const val H_LOWALPHA = 0x7FFFFFE00000000L
private const val L_ALPHA = L_LOWALPHA or L_UPALPHA
private const val H_ALPHA = H_LOWALPHA or H_UPALPHA
private const val L_ALPHANUM = L_DIGIT or L_ALPHA
private const val H_ALPHANUM = H_DIGIT or H_ALPHA
private const val L_HEX = L_DIGIT
private const val H_HEX = 0x7E0000007EL
private const val L_MARK = 0x678200000000L // -_.!~*'()
private const val H_MARK = 0x4000000080000000L
private const val L_UNRESERVED = L_ALPHANUM or L_MARK
private const val H_UNRESERVED = H_ALPHANUM or H_MARK
private const val L_RESERVED = -0x53ff67b000000000L // 0xAC00985000000000: ;/?:@&=+$,[]
private const val H_RESERVED = 0x28000001L
private const val L_ESCAPED = 1L // bit 0: escape pairs and visible non-US-ASCII chars (scanEscape)
private const val H_ESCAPED = 0L
private const val L_URIC = L_RESERVED or L_UNRESERVED or L_ESCAPED
private const val H_URIC = H_RESERVED or H_UNRESERVED or H_ESCAPED
private const val L_PCHAR = L_UNRESERVED or L_ESCAPED or 0x2400185000000000L // :@&=+$,
private const val H_PCHAR = H_UNRESERVED or H_ESCAPED or 0x1L
private const val L_PATH = L_PCHAR or 0x800800000000000L // ;/
private const val H_PATH = H_PCHAR
private const val L_DASH = 0x200000000000L
private const val H_DASH = 0x0L
private const val L_UNDERSCORE = 0L // Android's libcore only
private const val H_UNDERSCORE = 0x80000000L
private const val L_DOT = 0x400000000000L
private const val H_DOT = 0x0L
private const val L_USERINFO = L_UNRESERVED or L_ESCAPED or 0x2C00185000000000L // ;:&=+$,
private const val H_USERINFO = H_UNRESERVED or H_ESCAPED
private const val L_REG_NAME = L_UNRESERVED or L_ESCAPED or 0x2C00185000000000L // $,;:@&=+
private const val H_REG_NAME = H_UNRESERVED or H_ESCAPED or 0x1L
private const val L_SERVER = L_USERINFO or L_ALPHANUM or L_DASH or 0x400400000000000L // .:@[]
private const val H_SERVER = H_USERINFO or H_ALPHANUM or H_DASH or 0x28000001L
private const val L_SERVER_PERCENT = L_SERVER or 0x2000000000L // %
private const val H_SERVER_PERCENT = H_SERVER
private const val L_SCHEME = L_ALPHA or L_DIGIT or 0x680000000000L // +-.
private const val H_SCHEME = H_ALPHA or H_DIGIT
private const val L_SCOPE_ID = L_ALPHANUM or 0x400000000000L // _.
private const val H_SCOPE_ID = H_ALPHANUM or 0x80000000L
