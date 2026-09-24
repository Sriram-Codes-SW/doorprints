package app.doorprints

import android.app.Application
import android.net.Uri
import app.doorprints.ui.isWebLink
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.random.Random

/**
 * The house form's *Open* link (ADR-23 CMP-6 P6a): the common [isWebLink] must decide as the Android rule the form used
 * before, `Uri.parse(text)` with an http(s) scheme and a host that is not blank, on Android's own `Uri` (Robolectric
 * runs the framework's code). Fixed cases, and 20 000 strings built from the pieces that decide it (schemes, `//`,
 * user info, ports, the characters that end an authority, escapes that decode to spaces, malformed escapes).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: DoorprintsApp would start MapLibre (native code).
@Config(sdk = [35], application = Application::class)
class LinkParityTest {
    /** The form's rule before CMP-6 (HouseEditScreen in :app). */
    private fun androidRule(text: String): Boolean {
        val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    private fun check(text: String) = assertEquals("\"$text\"", androidRule(text), isWebLink(text))

    @Test
    fun fixedCases() {
        listOf(
            "", "http", "http:", "http:/", "http://", "http:///", "https://example.com", "HTTPS://Example.com/a?b#c",
            "http://example.com:8080/x", "http://user:pw@example.com", "http://user@", "http://@", "http://:80",
            "http://a:b", "http://[::1]:80/", "http://%20", "http://%20%20/x", "http://%C2%A0", "http://%", "http://%2",
            "http://%zz", "http://a%20b", "ftp://example.com", "mailto:someone@example.com", "example.com",
            "www.example.com/listing", " http://example.com", "http://example.com ", "http:\\\\example.com",
            "http://\\example.com", "http://ex ample.com", "http:// ", "http://\t", "https://99acres.com/x?y=1",
            "http://example.com#", "http://?q", "http://#f", "javascript:alert(1)", "http://१२३",
        ).forEach(::check)
    }

    @Test
    fun generatedStrings() {
        val pieces = listOf(
            "http", "https", "HTTP", "hTtPs", "ftp", ":", "://", "//", "/", "\\", "?", "#", "@", ":80", ":8a", "%20",
            "%09", "%C2%A0", "%E2%80%83", "%41", "%", "%2", "%zz", "%e0%a4", " ", "\t", "a", "example.com", "user:pw@",
            "[::1]", "0", "٣", "१", ".", "-", " ",
        )
        val random = Random(20260924)
        repeat(20_000) {
            val text = buildString { repeat(random.nextInt(1, 8)) { append(pieces[random.nextInt(pieces.size)]) } }
            check(text)
        }
    }
}
