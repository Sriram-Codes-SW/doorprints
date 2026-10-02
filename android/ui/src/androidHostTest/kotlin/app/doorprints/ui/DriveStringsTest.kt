package app.doorprints.ui

import app.doorprints.drive.connect.DriveMessage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Google Drive screens' strings (S4b-BL-117): every `drive_*` key in all four languages, the same placeholders,
 * a sentence for every [DriveMessage], the brand words (never "Restore" on a button), and the Hindi, Tamil and Telugu
 * blocks marked under review. The whole-file parity is also `StringParityTest` in `:app`.
 */
class DriveStringsTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        .resolve("ui/src/commonMain/composeResources")

    private fun read(lang: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(root.resolve("values$lang/strings.xml"))
        val nodes = doc.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
            .filter { it.getAttribute("name").startsWith("drive_") }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private val en = read("")
    private fun placeholders(s: String) = Regex("""%\d\$[sd]""").findAll(s).map { it.value }.toList()

    @Test
    fun everyLanguageHasEveryKeyWithTheSamePlaceholdersAndNoEmptyText() {
        assertTrue(en.size > 100, "the Drive keys are there")
        for (lang in listOf("-hi", "-ta", "-te")) {
            val other = read(lang)
            assertEquals(en.keys, other.keys, "keys in $lang")
            for ((k, v) in en) {
                assertTrue(other.getValue(k).isNotBlank(), "$lang $k is empty")
                assertEquals(placeholders(v), placeholders(other.getValue(k)), "$lang $k placeholders")
            }
        }
    }

    @Test
    fun everyMessageHasASentenceAndNothingElseIsHiding() {
        val expected = DriveMessage.entries.map { "drive_msg_" + it.name.lowercase() }.toSet()
        assertEquals(expected, en.keys.filter { it.startsWith("drive_msg_") }.toSet())
    }

    @Test
    fun brandWordsAndPlainSentences() {
        for (lang in listOf("", "-hi", "-ta", "-te")) {
            for ((k, v) in read(lang)) {
                assertFalse(Regex("(?i)restore|reset").containsMatchIn(v), "$lang $k: never Restore or Reset")
                assertFalse("\\'" in v, "$lang $k: no Android escapes in Compose resources")
            }
        }
        assertEquals("Import a backup", en["drive_import_row"])
        assertEquals("Save a copy first", en["drive_dlg_save_copy"])
        assertEquals("Delete for good", en["drive_dlg_delete"])
    }

    @Test
    fun hindiTamilAndTeluguAreMarkedUnderReview() {
        for (lang in listOf("-hi", "-ta", "-te")) {
            val text = root.resolve("values$lang/strings.xml").readText()
            val block = text.substringAfter("S4b-BL-117")
            assertTrue(block.substringBefore("-->").contains("Under review"), "$lang")
        }
    }
}
