package app.doorprints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The strings live in two places since ADR-23 CMP-2: the UI strings as Compose resources in :ui, the Android services'
 * strings (notifications, workers, the hunt service) as Android resources in :app. This keeps both sound in all four
 * languages (docs/06 TC-U-57):
 *  - every language has the same keys as English, in each place, and every plural has an "other" form;
 *  - every translation has the same placeholders as English, counted and in position;
 *  - the Compose side has only positional placeholders (%1$s, %1$d), the only ones Compose resources fill in, and none
 *    of Android's escapes (\' \" \@ \?), which Compose resources would show as written;
 *  - a key in both places reads the same in both, compared as each side shows it.
 */
class StringParityTest {
    private val languages = listOf("", "-hi", "-ta", "-te")
    private val android = root().resolve("app/src/main/res")
    private val compose = root().resolve("ui/src/commonMain/composeResources")

    private fun root(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir
    }

    /** key -> its texts as written in the file: one for a string, "quantity=text" per item for plurals. */
    private fun read(file: File): Map<String, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, List<String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            out[e.getAttribute("name")] = when (e.tagName) {
                "string" -> listOf(e.textContent)
                "plurals" -> (0 until e.getElementsByTagName("item").length).map {
                    val item = e.getElementsByTagName("item").item(it) as Element
                    item.getAttribute("quantity") + "=" + item.textContent
                }
                else -> continue
            }
        }
        return out
    }

    /** What Android shows for a resource text (aapt2): whitespace collapsed, bare quotes dropped, escapes resolved. */
    private fun androidValue(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        val text = raw.trim().replace(Regex("""\s+"""), " ")
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    val n = text[i + 1]
                    when (n) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t')
                        'u' -> { sb.append(text.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                        else -> sb.append(n)
                    }
                    i += 2
                    continue
                }
                c == '"' -> Unit
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** What Compose resources show: the text as written, with only \n, \t, \uXXXX and \\ resolved. */
    private fun composeValue(raw: String): String =
        Regex("""\\(n|t|\\|u[0-9a-fA-F]{4})""").replace(raw) {
            when (val v = it.groupValues[1]) { "n" -> "\n"; "t" -> "\t"; "\\" -> "\\"; else -> v.drop(1).toInt(16).toChar().toString() }
        }

    private val placeholder = Regex("""%(\d+\$)?[sd]""")

    /** The placeholders in order of position, bare ones numbered as Android numbers them (%s, %s -> %1$s, %2$s). */
    private fun placeholders(texts: List<String>): List<String> = texts.flatMap { t ->
        var bare = 0
        placeholder.findAll(t).map { m ->
            if (m.groupValues[1].isEmpty()) "%${++bare}$" + m.value.last() else m.value
        }.toList()
    }.sorted()

    private fun check(dir: File, where: String) {
        val english = read(dir.resolve("values/strings.xml"))
        assertTrue("$where: no English strings", english.isNotEmpty())
        for (lang in languages) {
            val other = read(dir.resolve("values$lang/strings.xml"))
            assertEquals("$where values$lang: keys", english.keys.toList(), other.keys.toList())
            for ((key, texts) in other) {
                if (texts.any { it.contains('=') } && texts.all { Regex("""^(zero|one|two|few|many|other)=""").containsMatchIn(it) }) {
                    assertTrue("$where values$lang/$key: plurals need an \"other\" form", texts.any { it.startsWith("other=") })
                }
                assertEquals("$where values$lang/$key: placeholders", placeholders(english.getValue(key)), placeholders(texts))
            }
        }
    }

    @Test fun androidResourcesAreCompleteInEveryLanguage() = check(android, "Android resources")

    @Test fun composeResourcesAreCompleteInEveryLanguage() = check(compose, "Compose resources")

    @Test fun composeResourcesHaveNoAndroidOnlySyntax() {
        for (lang in languages) {
            for ((key, texts) in read(compose.resolve("values$lang/strings.xml"))) {
                for (t in texts.map { it.substringAfter('=', it).let { v -> if (texts.size > 1 || t0(it)) v else it } }) {
                    val where = "values$lang/$key: \"$t\""
                    assertTrue("$where has a placeholder Compose resources do not fill (a literal % is shown as written)",
                        !Regex("""%(?!\d+\$[sd])""").containsMatchIn(t))
                    assertTrue("$where has an Android escape Compose resources show as written", !Regex("""\\['"@?]""").containsMatchIn(t))
                    assertTrue("$where has leading, trailing or double spaces", t == t.trim() && !t.contains("  "))
                }
            }
        }
    }

    /** A plurals item ("one=…"), as [read] writes it. */
    private fun t0(text: String) = Regex("""^(zero|one|two|few|many|other)=""").containsMatchIn(text)

    @Test fun keysInBothPlacesReadTheSame() {
        for (lang in languages) {
            val a = read(android.resolve("values$lang/strings.xml"))
            val c = read(compose.resolve("values$lang/strings.xml"))
            val shared = a.keys intersect c.keys
            assertTrue("no key is in both places (expected app_name at least)", shared.isNotEmpty())
            for (key in shared) {
                val androidShown = a.getValue(key).map { t -> androidValue(Regex("""(?<!%)%d""").replace(t) { "%1$" + "d" }) }
                assertEquals("values$lang/$key", androidShown, c.getValue(key).map(::composeValue))
            }
        }
    }
}
