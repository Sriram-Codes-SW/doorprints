package app.doorprints.shared.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Inflater

/**
 * The shared import vectors (`docs/schemas/import-vectors.json`, docs/schemas/README.md section 6.1): every `data.json`
 * check, every merge preview and every archive gets the same answer here as in the website's `backup-import.spec.ts`
 * (S4b-BL-75) and in Android's own `BackupReader` (`:app` `BackupReaderParityTest`, S4b-BL-76). An update file's
 * deletions (S4b-BL-82) are among them.
 */
class ImportVectorsTest {

    private val root: JsonObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, VECTORS).exists()) dir = dir.parentFile
        val file = File(checkNotNull(dir) { "$VECTORS not found" }, VECTORS)
        Json.parseToJsonElement(file.readText()).jsonObject.also {
            assertEquals("doorprints-import-vectors/1", it.getValue("format").jsonPrimitive.content)
        }
    }

    private fun cases(key: String): List<JsonObject> = root.getValue(key).jsonArray.map { it.jsonObject }

    private fun JsonObject.name() = getValue("name").jsonPrimitive.content
    private fun JsonObject.problem(): String? = get("problem")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    /** The `data.json` checks: decoding, then [BackupValidation.checkData]; a row that does not decode is BROKEN_DATA. */
    private fun check(data: JsonObject): BackupProblem? {
        val decoded = runCatching { BackupFormat.json.decodeFromJsonElement(BackupData.serializer(), data) }
            .getOrElse { return BackupProblem.BROKEN_DATA }
        return BackupValidation.checkData(decoded)
    }

    @Test
    fun everyDataCheckGivesTheSameAnswer() {
        val all = cases("checks")
        assertTrue(all.size >= 20)
        for (c in all) assertEquals(c.name(), c.problem(), check(c.getValue("data").jsonObject)?.name)
    }

    @Test
    fun everyMergePreviewCountsTheSame() {
        for (c in cases("merges")) {
            val name = c.name()
            val data = BackupFormat.json.decodeFromJsonElement(BackupData.serializer(), c.getValue("data"))
            assertNull(name, BackupValidation.checkData(data))
            val local = c.getValue("local").jsonObject
            fun versions(key: String) = local.getValue(key).jsonObject.mapValues { it.value.jsonPrimitive.long }
            fun ids(key: String) = local.getValue(key).jsonArray.mapTo(HashSet()) { it.jsonPrimitive.content }
            val mode = ImportMode.valueOf(c.getValue("mode").jsonPrimitive.content)
            val restore = c.getValue("restoreDeleted").jsonPrimitive.boolean
            val skip = c.getValue("skipUpdates").jsonPrimitive.boolean
            val update = c.getValue("update").jsonPrimitive.boolean
            val entries = c.getValue("photoEntries").jsonArray.mapTo(HashSet()) { it.jsonPrimitive.content }
            val preview = ImportPlan.preview(
                data, versions("houses"), versions("visits"), ids("photoIds"), entries, mode, ids("deletedHouses"),
                ids("scoredHouses"), restoreDeleted = restore, skipUpdates = skip, localUnlinkedVisitIds = ids("unlinkedVisits"),
                localBrokers = versions("brokers"), localViewings = versions("viewings"), applyDeletions = update,
            )
            val plan = ImportPlan.plan(
                data, versions("houses"), versions("visits"), ids("photoIds"), entries, mode, newId = { "n" + counter++ },
                locallyDeletedHouseIds = ids("deletedHouses"), restoreDeleted = restore, skipUpdates = skip,
                localUnlinkedVisitIds = ids("unlinkedVisits"), localBrokers = versions("brokers"),
                localViewings = versions("viewings"), applyDeletions = update,
            )
            val fields = fieldsOf(preview)
            for ((key, value) in c.getValue("expect").jsonObject) {
                val expected: Any = if (key == "isEmpty") value.jsonPrimitive.boolean else value.jsonPrimitive.int
                assertEquals("$name: $key", expected, fields[key] ?: error("$name: no field $key"))
            }
            assertEquals("$name: the plan deletes what the preview says", preview.removedHouses, plan.removedHouseIds.size)
            c["duplicates"]?.let {
                assertEquals("$name: duplicates", it.jsonPrimitive.int, ImportPlan.copyDuplicates(data, versions("houses"), ids("deletedHouses")))
            }
        }
    }

    private var counter = 0

    private fun fieldsOf(p: ImportPreview): Map<String, Any> = mapOf(
        "newHouses" to p.newHouses, "updatedHouses" to p.updatedHouses, "newerHereHouses" to p.newerHereHouses,
        "unchangedHouses" to p.unchangedHouses, "newVisits" to p.newVisits, "updatedVisits" to p.updatedVisits,
        "newPhotos" to p.newPhotos, "skippedPhotos" to p.skippedPhotos, "photosMissingFromFile" to p.photosMissingFromFile,
        "checklistsCleared" to p.checklistsCleared, "deletedHereHouses" to p.deletedHereHouses,
        "restoredHouses" to p.restoredHouses, "keptMineHouses" to p.keptMineHouses, "newBrokers" to p.newBrokers,
        "updatedBrokers" to p.updatedBrokers, "newViewings" to p.newViewings, "updatedViewings" to p.updatedViewings,
        "removedHouses" to p.removedHouses, "isEmpty" to p.isEmpty,
    )

    @Test
    fun everyArchiveOpensOrIsRefusedTheSame() {
        for (c in cases("archives")) {
            val name = c.name()
            val bytes = Base64.getDecoder().decode(c.getValue("base64").jsonPrimitive.content)
            when (val opened = BackupArchive.open(MemorySource(bytes), tools)) {
                is ArchiveOpen.Failed -> assertEquals(name, c.problem(), opened.problem.name)
                is ArchiveOpen.Ok -> {
                    assertNull("$name opened", c.problem())
                    val archive = opened.archive
                    assertEquals(name, c.getValue("houses").jsonPrimitive.int, archive.data.houses.size)
                    assertEquals(name, (c.getValue("photoEntries") as JsonArray).map { it.jsonPrimitive.content }.toSet(), archive.photoEntries)
                    c["photoOk"]?.let { ok ->
                        val photo = archive.photoBytes("photos/p1.jpg")
                        if (ok.jsonPrimitive.boolean) assertArrayEquals(name, PHOTO, photo) else assertNull(name, photo)
                    }
                    c["sharedTo"]?.let { assertEquals(name, it.jsonPrimitive.content, archive.manifest?.sharedTo) }
                    assertEquals(name, c["update"]?.jsonPrimitive?.boolean ?: false, ImportPlan.isUpdate(archive.manifest))
                }
            }
        }
    }

    private class MemorySource(private val bytes: ByteArray) : RandomSource {
        override val size: Long get() = bytes.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return 0
            val n = minOf(length.toLong(), bytes.size - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            return n
        }
    }

    private val tools = ArchiveTools(
        sha256 = {
            object : Sha256 {
                val digest = MessageDigest.getInstance("SHA-256")
                override fun update(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
                override fun digest(): ByteArray = digest.digest()
            }
        },
        inflate = { compressed, max ->
            val inflater = Inflater(true)
            try {
                inflater.setInput(compressed)
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var over = false
                while (!inflater.finished() && !over) {
                    val n = inflater.inflate(chunk)
                    require(n > 0 || !inflater.needsInput()) { "cut short" }
                    if (out.size() + n > max) over = true else out.write(chunk, 0, n)
                }
                if (over) null else out.toByteArray()
            } finally {
                inflater.end()
            }
        },
    )

    companion object {
        const val VECTORS = "docs/schemas/import-vectors.json"

        /** The photo of the vectors' backups: byte `i` is `i * 31 mod 256`, 300 bytes. */
        val PHOTO = ByteArray(300) { (it * 31 % 256).toByte() }
    }
}
