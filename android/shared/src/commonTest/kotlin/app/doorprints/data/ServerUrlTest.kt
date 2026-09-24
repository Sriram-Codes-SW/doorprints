package app.doorprints.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F-02 / SEC-004: only HTTPS, except the local development hosts allowed by network_security_config.xml.
 *
 * Moved from `:app` (JUnit) to commonTest in CMP-4 P4b; the first six tests are unchanged. The rest pin the parts of
 * `java.net.URI`'s parser that `UriParts` re-implements (ports, IPv4, IPv6, host names, escapes, empty parts); each
 * expected value is what the `java.net.URI` version gave (`ServerUrlParityTest` compares the two on the JVM), and
 * for `_` in a host name what Android's `java.net.URI` gives.
 */
class ServerUrlTest {

    @Test
    fun acceptsHttpsAndTrimsTrailingSlash() {
        assertEquals(ServerUrl.Result.Ok("https://api.example.com"), ServerUrl.check(" https://api.example.com/ "))
        assertEquals(ServerUrl.Result.Ok("https://api.example.com:8443/base"), ServerUrl.check("https://api.example.com:8443/base"))
    }

    @Test
    fun allowsCleartextOnlyForLocalHosts() {
        assertEquals(ServerUrl.Result.Ok("http://10.0.2.2:8080"), ServerUrl.check("http://10.0.2.2:8080"))
        assertEquals(ServerUrl.Result.Ok("http://localhost:8080"), ServerUrl.check("http://localhost:8080"))
        assertEquals(ServerUrl.Result.NotHttps, ServerUrl.check("http://192.168.1.10:8080"))
        assertEquals(ServerUrl.Result.NotHttps, ServerUrl.check("http://my-api.onrender.com"))
    }

    @Test
    fun rejectsJunk() {
        assertEquals(ServerUrl.Result.Empty, ServerUrl.check("   "))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("ftp://example.com"))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("example.com"))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("https://user:pass@example.com"))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("https://example.com/?x=1"))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("https://exa mple.com"))
    }

    @Test
    fun schemeAndLocalHostAreCaseInsensitive() {
        assertEquals(ServerUrl.Result.Ok("HTTPS://Example.COM"), ServerUrl.check("HTTPS://Example.COM"))
        assertEquals(ServerUrl.Result.Ok("http://LOCALHOST:8080"), ServerUrl.check("http://LOCALHOST:8080"))
        assertEquals(ServerUrl.Result.Ok("http://127.0.0.1:8080"), ServerUrl.check("http://127.0.0.1:8080/"))
        assertEquals(ServerUrl.Result.Ok("https://example.com"), ServerUrl.check("https://example.com///"))
    }

    @Test
    fun lookAlikeLocalHostsNeedHttps() {
        assertEquals(ServerUrl.Result.NotHttps, ServerUrl.check("http://localhost.evil.example"))
        assertEquals(ServerUrl.Result.NotHttps, ServerUrl.check("http://127.0.0.2:8080"))
        assertEquals(ServerUrl.Result.NotHttps, ServerUrl.check("http://[::1]:8080"))
    }

    @Test
    fun rejectsUrlsWithoutAHost() {
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("https://"))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("javascript:alert(1)"))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("https://example.com#top"))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("/api"))
    }

    @Test
    fun portsAsJavaNetUriReadsThem() {
        ok("https://example.com:")
        ok("https://example.com:99999") // java.net.URI checks only that the port fits an Int
        invalid("https://example.com:99999999999")
        invalid("https://example.com:80:90")
        invalid("https://example.com:8a")
    }

    @Test
    fun ipv4AndHostNamesAsJavaNetUriReadsThem() {
        ok("https://1.2.3.4")
        ok("https://example.com.")
        ok("https://123")
        ok("https://123.a")
        invalid("https://1.2.3.4.5") // the last label of a dotted name must start with a letter
        invalid("https://a.123")
        invalid("https://192.168.1.300")
        invalid("https://a-.com")
        invalid("https://-a.com")
        assertEquals(ServerUrl.Result.NotHttps, ServerUrl.check("http://localhost."))
        assertEquals(ServerUrl.Result.NotHttps, ServerUrl.check("http://010.0.2.2"))
        invalid("http://127.0.0.1.")
    }

    @Test
    fun underscoresInHostNamesAsAndroidReadsThem() {
        // Android's java.net.URI (libcore) allows '_' after a label's first char; OpenJDK's does not (it reads such an
        // authority as a registry name, with no host). The app ran on Android's, so these keep its answers.
        ok("https://ex_ample.com")
        ok("https://a_.example.com")
        assertEquals(ServerUrl.Result.NotHttps, ServerUrl.check("http://my_pc:8080"))
        invalid("https://_a.example.com")
        invalid("https://a._b.c")
        ok("https://a_b_.c")
        invalid("https://ex_ample.com", underscoreInHostnames = false)
    }

    @Test
    fun ipv6LiteralsAsJavaNetUriReadsThem() {
        ok("https://[::1]")
        ok("https://[1:2:3:4:5:6:7:8]")
        ok("https://[::ffff:1.2.3.4]")
        ok("https://[fe80::1%eth0]")
        invalid("https://[1:2:3:4:5:6:7]")
        invalid("https://[fe80::1%]")
        invalid("https://[::1")
        invalid("https://[::1]]")
        invalid("https://[12345::1]")
    }

    @Test
    fun escapesAndOtherCharsAsJavaNetUriReadsThem() {
        ok("https://example.com/%41")
        ok("https://example.com/a@b")
        ok("https://x.com/a;b=c")
        ok("https://example.com/\u092E\u0915\u093E\u0928") // visible non-ASCII chars are allowed in a path
        invalid("https://example.com/%zz")
        invalid("https://ex%41mple.com")
        invalid("https://ex\u00E4mple.com")
        invalid("https://example.com/ a")
        invalid("https://example.com/a\u00A0b") // a no-break space is a space char
        invalid("https://x.com/a[b]")
        invalid("https://example.com\\")
    }

    @Test
    fun emptyPartsAndSchemesAsJavaNetUriReadsThem() {
        invalid("https:///path")
        invalid("https://example.com?")
        invalid("https://example.com#")
        invalid("https://@example.com")
        invalid("https:example.com")
        invalid("1https://x.com")
        invalid("h+t.-p://x.com")
        assertEquals(ServerUrl.Result.Ok("HtTp://LocalHost"), ServerUrl.check("HtTp://LocalHost"))
    }

    private fun ok(url: String) = assertEquals(ServerUrl.Result.Ok(url), ServerUrl.check(url), url)

    private fun invalid(url: String, underscoreInHostnames: Boolean = true) =
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check(url, underscoreInHostnames), url)
}
