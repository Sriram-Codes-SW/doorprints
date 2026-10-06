package app.doorprints.ui.drive

import app.doorprints.drive.connect.DriveReason
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Drive screens' strings: every key the screens and the controller can produce has a resource in all four languages,
 * the key table is the resource files, and a sentence the website has reads exactly as on the website (docs/ops/android-drive-ui-notes.md).
 */
class DriveStringsTest {
    private val languages = mapOf("en" to "values", "hi" to "values-hi", "ta" to "values-ta", "te" to "values-te")

    private fun root(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir
    }

    private fun resources(lang: String): Map<String, String> {
        val file = root().resolve("ui/src/commonMain/composeResources/${languages.getValue(lang)}/strings.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
        val out = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.takeIf { it.tagName == "string" }?.let { out[it.getAttribute("name")] = it.textContent }
        return out
    }

    private fun webDictionary(lang: String): Map<String, String> {
        val text = root().parentFile.resolve("web/src/app/i18n/$lang.ts").readText()
        val re = Regex("""^\s*'((?:drive|common)[A-Za-z0-9_.]*)':\s*((?:'(?:[^'\\]|\\.)*')|(?:"(?:[^"\\]|\\.)*"))\s*,""", RegexOption.MULTILINE)
        return re.findAll(text).associate { m -> m.groupValues[1] to m.groupValues[2].drop(1).dropLast(1).replace(Regex("""\\(.)"""), "$1") }
    }

    /** The website's `{name}` placeholders as positional ones, in order of first appearance (`d` for counts, `s` otherwise). */
    private fun positional(text: String): String {
        val names = mutableListOf<String>()
        return Regex("""\{(\w+)}""").replace(text) { m ->
            val n = m.groupValues[1]
            if (n !in names) names += n
            "%" + (names.indexOf(n) + 1) + "$" + if (n in setOf("n", "count", "left", "total", "seconds")) "d" else "s"
        }
    }

    private val notes: List<String> by lazy {
        val text = root().parentFile.resolve("docs/ops/android-drive-ui-notes.md").readText()
        text.substringAfter("<!-- keys:start -->").substringBefore("<!-- keys:end -->").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }
    }

    private val phoneOnly by lazy { notes.filter { it.startsWith("phone-only ") }.map { it.substringAfter(' ') } }
    private val webKeys by lazy { notes.filter { it.startsWith("web ") }.associate { l -> l.split(' ').let { it[1] to it[2] } } }

    @Test fun theTableIsExactlyTheDriveResourcesOfEveryLanguage() {
        val expected = DRIVE_STRINGS.keys.map(::driveResName).toSet()
        for (lang in languages.keys) {
            val have = resources(lang).keys.filter { it.startsWith("drive_") }.toSet()
            assertEquals(expected, have, "values for $lang")
        }
        assertEquals(DRIVE_STRINGS.size, expected.size, "two keys map to one resource name")
    }

    @Test fun everyReasonTheControllerCanGiveHasATranslatedText() {
        for (reason in DriveReason.entries) {
            assertTrue(reason.key in DRIVE_STRINGS, "no text for ${reason.key}")
            for (lang in languages.keys) assertTrue(resources(lang).getValue(driveResName(reason.key)).isNotBlank(), "${reason.key} in $lang")
        }
    }

    @Test fun everyKeyTheScreensAskForIsInTheTable() {
        val dir = root().resolve("ui/src/commonMain/kotlin/app/doorprints/ui/drive")
        val used = mutableSetOf<String>()
        for (file in dir.listFiles().orEmpty().filter { it.name.endsWith(".kt") && it.name != "DriveStringTable.kt" }) {
            Regex("""\bt\("((?:drive|common)[A-Za-z0-9_.]*)"""").findAll(file.readText()).forEach { used += it.groupValues[1] }
            Regex(""""(drive(?:Connect|Sync|Backups|Delete|Devices|Enrol|Join|Problem)\.[A-Za-z0-9_.]*|drivePhotos\.[A-Za-z]*)"""").findAll(file.readText()).forEach { used += it.groupValues[1] }
        }
        val missing = used.filter { it !in DRIVE_STRINGS }
        assertTrue(missing.isEmpty(), "keys with no resource: $missing")
        assertTrue(used.size > 80, "the scan found ${used.size} keys")
    }

    @Test fun theNotesListEveryKeyOnce() {
        val listed = webKeys.keys + phoneOnly
        assertEquals(DRIVE_STRINGS.keys, listed.toSet())
        assertEquals(listed.size, listed.toSet().size)
    }

    @Test fun aSentenceTheWebsiteHasReadsAsOnTheWebsiteInEveryLanguage() {
        for (lang in languages.keys) {
            val web = webDictionary(lang)
            val res = resources(lang)
            for ((key, webKey) in webKeys) {
                val expected = positional(web[webKey] ?: error("$webKey missing in the website's $lang dictionary"))
                assertEquals(expected, res[driveResName(key)], "$key ($lang) vs the website's $webKey")
            }
        }
    }

    @Test fun phoneOnlySentencesAreTranslatedNotCopiedAndKeepTheirPlaceholders() {
        val en = resources("en")
        for (key in phoneOnly) {
            val name = driveResName(key)
            for (lang in listOf("hi", "ta", "te")) {
                val text = resources(lang).getValue(name)
                assertTrue(text != en.getValue(name), "$key is English in $lang")
                assertEquals(Regex("""%\d\$[sd]""").findAll(en.getValue(name)).map { it.value }.sorted().toList(), Regex("""%\d\$[sd]""").findAll(text).map { it.value }.sorted().toList(), "$key ($lang) placeholders")
            }
        }
    }

    @Test fun noPhoneSentenceSaysBrowserOrWebsiteOrRestoreOrPasskey() {
        val en = resources("en")
        for ((name, text) in en.filterKeys { it.startsWith("drive_") }) {
            val lower = text.lowercase()
            assertTrue("browser" !in lower && "website" !in lower, "$name: $text")
            assertTrue("passkey" !in lower, "$name: $text")
            assertTrue("restore" !in lower, "$name: $text")
        }
    }

    @Test fun theBrandWordsAreExact() {
        val en = resources("en")
        assertEquals("Import a backup", en["drive_connect_import_from_drive"])
        assertEquals("Import a backup", en["drive_backups_import"])
        assertEquals("Save a copy first", en["drive_delete_save_copy_first"])
    }
}
