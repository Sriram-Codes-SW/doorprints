package app.doorprints

import app.doorprints.export.BackupOpen
import app.doorprints.export.BackupReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * Android's `java.util.zip` [BackupReader] gives every archive of the shared vectors (`docs/schemas/import-vectors.json`)
 * the answer the common reader gives in `:shared`'s `ImportVectorsTest` and the website in `backup-import.spec.ts`: the
 * same refusal, or the same rows, photo entries and photo bytes (S4b-BL-76, S4b-BL-75).
 */
class BackupReaderParityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val vectors by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, VECTORS).exists()) dir = dir.parentFile
        Json.parseToJsonElement(File(checkNotNull(dir) { "$VECTORS not found" }, VECTORS).readText()).jsonObject
    }

    @Test
    fun everyArchiveGetsTheCommonReadersAnswer() {
        val archives = vectors.getValue("archives").jsonArray.map { it.jsonObject }
        assertTrue(archives.size >= 15)
        for ((i, c) in archives.withIndex()) {
            val name = c.getValue("name").jsonPrimitive.content
            val file = temp.newFile("vector-$i")
            file.writeBytes(Base64.getDecoder().decode(c.getValue("base64").jsonPrimitive.content))
            val problem = c["problem"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            when (val opened = BackupReader.open(file)) {
                is BackupOpen.Failed -> assertEquals(name, problem, opened.problem.name)
                is BackupOpen.Ok -> opened.reader.use { reader ->
                    assertNull("$name opened", problem)
                    assertEquals(name, c.getValue("houses").jsonPrimitive.int, reader.data.houses.size)
                    assertEquals(name, c.getValue("photoEntries").jsonArray.map { it.jsonPrimitive.content }.toSet(), reader.photoEntries)
                    c["photoOk"]?.let { ok ->
                        val photo = reader.photoBytes("photos/p1.jpg")
                        if (ok.jsonPrimitive.boolean) assertArrayEquals(name, PHOTO, photo) else assertNull(name, photo)
                    }
                    c["sharedTo"]?.let { assertEquals(name, it.jsonPrimitive.content, reader.manifest?.sharedTo) }
                }
            }
        }
    }

    private companion object {
        const val VECTORS = "docs/schemas/import-vectors.json"
        val PHOTO = ByteArray(300) { (it * 31 % 256).toByte() }
    }
}
