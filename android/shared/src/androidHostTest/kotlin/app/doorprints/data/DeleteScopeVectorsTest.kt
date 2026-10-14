/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.data

import android.app.Application
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import app.doorprints.shared.model.DeleteScope
import app.doorprints.shared.model.ScopedCandidate
import app.doorprints.shared.model.SpaceScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * The blast-radius invariant of the deletion safeguards on the phones (deletion safeguards PR 3, S4b-BL-287; docs/02 T-D), on
 * real Room, against `docs/schemas/delete-scope-vectors.json`, the file the website's `delete-scope-vectors.spec.ts` reads:
 * an action in one space (delete, undo, restore from *Recently deleted*, *Delete all in this space*, the list) writes only
 * the rows it names, and every other row, of any kind, is byte-identical before and after. The vector file is never edited
 * to make this pass. Not in commonTest: it reads repository files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeleteScopeVectorsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

    private class MemorySecrets(name: String) : SecretStore {
        private val key = stringPreferencesKey(name)
        override fun get(settings: Preferences): String? = settings[key]
        override fun put(settings: MutablePreferences, apiKey: String) {
            settings[key] = apiKey
        }
        override fun clear(settings: MutablePreferences) {
            settings.remove(key)
        }
    }

    /** The repository with a clock the case sets: a local edit is stamped `max(clock, previous + 1)`. */
    private class ClockRepository(db: AppDatabase, settings: SettingsStore, photoDir: String) : CommonRepository(
        db, settings, photoDir = photoDir, syncSoon = {}, apiFor = { _, _ -> error("no server") },
    ) {
        var clock = 0L
        override fun now(): Long = clock
    }

    private val settings by lazy {
        SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "delete.preferences_pb") },
            MemorySecrets("serverKey"), geminiSecrets = MemorySecrets("aiKey"),
        )
    }
    private val repo by lazy { ClockRepository(db, settings, File(tmp.root, "photos").path) }

    @After
    fun close() {
        db.close()
        scope.cancel()
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val vectors = Json.parseToJsonElement(repoFile("docs/schemas/delete-scope-vectors.json").readText()).jsonObject
    private val cases = vectors.getValue("cases").jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun seed(o: JsonObject) = HouseEntity(
        id = o.text("id")!!, label = o.text("label")!!, lat = 13.0, lon = 80.0, createdAt = ms("2026-09-01T00:00:00.000Z"),
        updatedAt = ms(o.text("updatedAt")!!), deleted = o.flag("deleted")!!, dirty = o.flag("dirty")!!, kind = o.text("kind") ?: "house",
    )

    private fun shape(h: HouseEntity) = listOf(h.id, h.kind, h.label, h.deleted, h.dirty, h.updatedAt)

    private suspend fun all() = db.houses().all() + db.houses().deleted()

    @Test
    fun theFormatIsTheOneTheWebsiteReads() {
        assertEquals("doorprints-delete-scope-vectors/1", vectors.getValue("format").jsonPrimitive.content)
        assertEquals(11, cases.size)
    }

    @Test
    fun everyCaseWritesOnlyTheNamedRowsAndLeavesEveryOtherRowByteIdentical() = runBlocking {
        for (c in cases) {
            val name = c.text("name")!!
            db.clearAllTables()
            val seeds = c.getValue("seed").jsonArray.map { seed(it.jsonObject) }
            for (h in seeds) db.houses().upsert(h)
            val before = all().associateBy { it.id }
            val multi = c.flag("multi") ?: false
            val default = c.text("default") ?: "house"
            val available = c["available"]?.let { c.strings("available") } ?: listOf("house")
            for (step in c.getValue("steps").jsonArray.map { it.jsonObject }) {
                repo.clock = ms(step.text("at")!!)
                val id = step.text("id")!!
                when (step.text("op")) {
                    "delete" -> repo.deleteHouse(id)
                    "undo", "restore" -> repo.restoreHouse(id)
                    "deleteSpace" -> {
                        // The card's rule (KindsSettingsSection): the live rows whose space is this kind, nothing else.
                        val rows = all().map { ScopedCandidate(it.id, it.kind, deleted = it.deleted) }
                        repo.deleteHouses(DeleteScope.liveIdsOf(rows, SpaceScope.holdsFor(id, multi, default, available)))
                    }
                    else -> error("unknown op in $name")
                }
            }
            val after = all().associateBy { it.id }
            val expect = c.getValue("expect").jsonObject
            for (id in expect.strings("unchanged")) assertEquals("$name: $id", before[id], after[id])
            expect["rows"]?.jsonArray?.map { it.jsonObject }?.let { rows ->
                val want = rows.map { r -> listOf(r.text("id"), r.text("kind"), seeds.first { it.id == r.text("id") }.label, r.flag("deleted"), r.flag("dirty"), ms(r.text("updatedAt")!!)) }
                assertEquals(name, want.sortedBy { it[0] as String }, after.values.map(::shape).sortedBy { it[0] as String })
            }
            expect["bin"]?.jsonObject?.let { bins ->
                val now = ms(c.text("now")!!)
                val rows = all().map { ScopedCandidate(it.id, it.kind, deleted = it.deleted, updatedAt = it.updatedAt) }
                for ((kind, ids) in bins) {
                    val got = DeleteScope.binRows(rows, SpaceScope.holdsFor(kind, multi, default, available), now).map { it.id }
                    assertEquals("$name: $kind", ids.jsonArray.map { it.jsonPrimitive.content }, got)
                }
            }
        }
    }

    @Test
    fun aRestoreOfARowThatIsNotDeletedIsRefusedAndABulkDeleteSkipsTheDeleted() = runBlocking {
        db.houses().upsert(seed(Json.parseToJsonElement("""{"id":"a","label":"a","deleted":false,"dirty":false,"updatedAt":"2026-10-01T09:00:00.000Z"}""").jsonObject))
        db.houses().upsert(seed(Json.parseToJsonElement("""{"id":"b","label":"b","deleted":true,"dirty":false,"updatedAt":"2026-10-02T09:00:00.000Z"}""").jsonObject))
        repo.clock = ms("2026-10-14T12:00:00.000Z")
        assertFalse(repo.restoreHouse("a"))
        assertFalse(repo.restoreHouse("missing"))
        assertEquals(listOf("a"), repo.deleteHouses(listOf("a", "b", "missing")))
        assertEquals(ms("2026-10-02T09:00:00.000Z"), db.houses().get("b")!!.updatedAt)
        assertEquals(listOf("a"), repo.restoreHouses(listOf("a", "b")).filter { it == "a" })
        assertTrue(repo.deleteHouses(emptyList()).isEmpty())
    }

    @Test
    fun aDeleteOnAClockThatIsBehindStillMovesTheStampPastTheLiveRow() = runBlocking {
        db.houses().upsert(seed(Json.parseToJsonElement("""{"id":"f","label":"f","deleted":false,"dirty":false,"updatedAt":"2026-10-20T09:00:00.000Z"}""").jsonObject))
        repo.clock = ms("2026-10-14T12:00:00.000Z")
        repo.deleteHouses(listOf("f"))
        assertEquals(ms("2026-10-20T09:00:00.000Z") + 1, db.houses().get("f")!!.updatedAt)
    }
}
