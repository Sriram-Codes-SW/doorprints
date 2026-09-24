package app.doorprints.data

import java.net.URI
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The common [ServerUrl] gives the same answer as the `java.net.URI` version it replaced in CMP-4 P4b (ADR-23). The
 * reference below is that version's `check`, unchanged. Run on the JVM (androidHostTest), where `java.net.URI` is
 * OpenJDK's: the port is compared with the JVM's host name rule, and, for strings without a '_', as the app runs it
 * (Android's rule, which also allows '_' in a host name; `ServerUrlTest` pins those cases). The strings: the fixed
 * cases, then generated strings built from the characters the parser treats specially, mutations of real addresses,
 * and IPv6 and dotted literals: 300 000 in all. The seeds are fixed, so a failure names a string that repeats.
 */
class ServerUrlParityTest {

    private fun reference(raw: String): ServerUrl.Result {
        val text = raw.trim().trimEnd('/')
        if (text.isEmpty()) return ServerUrl.Result.Empty
        val uri = runCatching { URI(text) }.getOrNull() ?: return ServerUrl.Result.Invalid
        val scheme = uri.scheme?.lowercase() ?: return ServerUrl.Result.Invalid
        val host = uri.host?.lowercase() ?: return ServerUrl.Result.Invalid
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return ServerUrl.Result.Invalid
        return when (scheme) {
            "https" -> ServerUrl.Result.Ok(text)
            "http" -> if (host in ServerUrl.LOCAL_HOSTS) ServerUrl.Result.Ok(text) else ServerUrl.Result.NotHttps
            else -> ServerUrl.Result.Invalid
        }
    }

    private fun assertSame(url: String) {
        assertEquals(reference(url), ServerUrl.check(url, underscoreInHostnames = false), "for \"$url\"")
        // Android's own parser (libcore ojluni/src/main/java/java/net/URI.java, "Android-changed: Allow underscore in
        // hostname") differs from the JVM's in its parser only by allowing '_' in host names; ServerUrlTest pins those
        // cases, so the default mode is compared here only on strings without '_'.
        if ('_' !in url) assertEquals(reference(url), ServerUrl.check(url), "for \"$url\"")
    }

    private val seeds = listOf(
        "https://api.example.com", "https://api.example.com:8443/base", "http://10.0.2.2:8080", "http://localhost:8080",
        "http://192.168.1.10:8080", "https://user:pass@example.com", "https://example.com/?x=1", "http://[::1]:8080",
        "https://[fe80::1%eth0]:443/p", "https://[::ffff:1.2.3.4]", "https://[1:2:3:4:5:6:7:8]", "https://1.2.3.4",
        "https://example.com.", "https://ex_ample.com", "https://example.com/%41", "https://example.com/a@b",
        "javascript:alert(1)", "https://example.com#top", "/api", "https://", "mailto:a@b.c", "file:///etc/passwd",
        "https://my-api.onrender.com/", "http://127.0.0.1:8080/", "HTTPS://Example.COM",
        "https://example.com/मकान", "https://exämple.com", "https://example.com/a b",
        "https://[12345::1]", "https://a.b-c.d:0/e;f=g/h",
    )

    // Characters java.net.URI treats specially, a few ordinary ones, and non-ASCII: visible, spaces (U+00A0, U+2028,
    // U+3000), controls (U+0085, NUL, LF) and a lone surrogate.
    private val alphabet = "aZz09-._~!$&'()*+,;=:@/?#[]%fF1 \t\\\"<>^`{|}ä \u0085 म\u0000\u3000\uD800\n"

    @Test
    fun theFixedCasesMatchJavaNetUri() {
        seeds.forEach(::assertSame)
    }

    @Test
    fun generatedAddressesMatchJavaNetUri() {
        val random = Random(20260924)
        val prefixes = listOf("https://", "http://", "HTTP://", "https:", "http:/", "h:", "", "//", "https://[")
        val hosts = listOf("localhost", "127.0.0.1", "10.0.2.2", "example.com", "1.2.3.4", "[::1]", "[a:b::c]", "")
        repeat(100_000) {
            val tail = buildString {
                repeat(random.nextInt(0, 12)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            val url = prefixes.random(random) + hosts.random(random) + tail
            assertSame(url)
        }
    }

    @Test
    fun mutatedAddressesMatchJavaNetUri() {
        val random = Random(9242026)
        repeat(100_000) {
            val chars = seeds.random(random).toCharArray().toMutableList()
            repeat(random.nextInt(1, 4)) {
                val at = random.nextInt(chars.size + 1)
                when (random.nextInt(3)) {
                    0 -> chars.add(at, alphabet[random.nextInt(alphabet.length)])
                    1 -> if (at < chars.size) chars.removeAt(at)
                    else -> if (at < chars.size) chars[at] = alphabet[random.nextInt(alphabet.length)]
                }
            }
            assertSame(chars.joinToString(""))
        }
    }

    @Test
    fun randomIpv6AndIpv4LiteralsMatchJavaNetUri() {
        val random = Random(42)
        val parts = listOf("0", "1", "ff", "FFFF", "12345", "", ":", "::", "1.2.3.4", "256.1.1.1", "%eth0", "%", ".")
        repeat(50_000) {
            val literal = buildString { repeat(random.nextInt(1, 10)) { append(parts.random(random)); append(":") } }
            assertSame("https://[" + literal.dropLast(random.nextInt(0, 2)) + "]:8080")
            val bytes = listOf("0", "00", "255", "256", "010", "1", "a", "")
            val dotted = List(random.nextInt(1, 6)) { bytes.random(random) }
            assertSame("http://" + dotted.joinToString("."))
        }
    }
}
