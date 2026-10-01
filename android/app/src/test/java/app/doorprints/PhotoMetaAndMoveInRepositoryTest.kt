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

package app.doorprints

import android.app.Application
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.doorprints.data.AndroidRepository
import app.doorprints.data.AppDatabase
import app.doorprints.data.DatabaseFile
import app.doorprints.data.HouseEntity
import app.doorprints.data.PhotoEntity
import app.doorprints.data.SecretStore
import app.doorprints.data.SettingsStore
import app.doorprints.data.create
import app.doorprints.shared.api.ApiClient
import app.doorprints.shared.api.ApiHttp
import app.doorprints.shared.export.ExportPhoto
import app.doorprints.shared.export.ImportActions
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.MoveIn
import app.doorprints.shared.model.MoveInItem
import app.doorprints.shared.model.PhotoMeta
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Slice 5 on the app's own database (Robolectric) against a fake server (Ktor's MockEngine): at most one TAKEN house,
 * *Mark them Not chosen* / *Close this hunt*, a photo's meta edited here (stamped and sent with
 * `PUT /api/photos/{id}/meta` after the upload), pulled from the change feed and imported, each time last write wins
 * on `metaUpdatedAt`; and the move-in kept with the house.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhotoMetaAndMoveInRepositoryTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: AndroidRepository

    private object MemorySecrets : SecretStore {
        private val key = stringPreferencesKey("testKey")
        override fun get(settings: Preferences): String? = settings[key]
        override fun put(settings: MutablePreferences, apiKey: String) {
            settings[key] = apiKey
        }
        override fun clear(settings: MutablePreferences) {
            settings.remove(key)
        }
    }

    /** The fake server: the photo feed, the meta PUT's answer (or a status), and every request with its body. */
    private class Server {
        var photoChanges = "[]"
        var metaAnswer: String? = null
        var metaStatus = HttpStatusCode.OK
        val seen = mutableListOf<String>()
        val bodies = mutableListOf<String>()

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val method = request.method.value
            seen += "$method $path"
            (request.body as? TextContent)?.let { bodies += it.text }
            val meta = method == "PUT" && path.endsWith("/meta")
            val body = when {
                path == "/api/stats" -> "{\"houses\":1,\"shortlisted\":0,\"rejected\":0,\"visits\":0,\"streets\":0}"
                method == "GET" && path == "/api/photos" -> photoChanges
                meta -> metaAnswer ?: bodies.last().let { "{\"id\":\"p1\",\"houseId\":\"h1\"," + it.removePrefix("{") }
                method == "PUT" -> bodies.last()
                method == "POST" -> "{}"
                else -> "[]"
            }
            respond(body, if (meta) metaStatus else HttpStatusCode.OK, Headers.build { append("Content-Type", "application/json") })
        }
    }

    private val server = Server()
    private val at = 1_760_000_000_000

    private fun house(id: String, status: HouseStatus) =
        HouseEntity(id = id, label = id, lat = 12.97, lon = 77.59, status = status, createdAt = at, updatedAt = at, dirty = false)

    @Before
    fun setUp(): Unit = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        context.deleteDatabase(DatabaseFile.NAME)
        db = AppDatabase.create(context)
        settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "meta-test.preferences_pb") },
            MemorySecrets,
        )
        settings.saveServer("https://sync.example", "test-" + UUID.randomUUID())
        repo = AndroidRepository(context, db, settings) { url, key ->
            ApiClient(url, key, ApiHttp.client(server.engine), callTimeoutMs = null)
        }
        db.houses().upsert(house("h1", HouseStatus.SHORTLISTED))
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(DatabaseFile.NAME)
        File(context.filesDir, "meta-test.preferences_pb").delete()
        repo.photoFile("p1").delete()
    }

    @Test
    fun m1_savingATakenHouseReturnsThePreviousTakenOneToShortlistedAndKeepsItsMoveIn(): Unit = runBlocking {
        db.houses().upsert(house("h2", HouseStatus.TAKEN))
        db.houses().upsert(house("h3", HouseStatus.REJECTED))
        val moveIn = MoveIn(items = listOf(MoveInItem("mi_keys", "Keys received", true, 0), MoveInItem("a/b", "Bad id")))
        repo.saveHouse(db.houses().get("h1")!!.copy(status = HouseStatus.TAKEN, moveIn = moveIn))
        assertEquals(HouseStatus.TAKEN, db.houses().get("h1")!!.status)
        assertEquals(HouseStatus.SHORTLISTED, db.houses().get("h2")!!.status)
        assertTrue("the demoted house is sent too", db.houses().get("h2")!!.dirty)
        assertEquals(HouseStatus.REJECTED, db.houses().get("h3")!!.status)
        assertEquals(listOf(MoveInItem("mi_keys", "Keys received", true, 0)), db.houses().get("h1")!!.moveIn!!.items)
        assertEquals(1, db.houses().all().count { it.status == HouseStatus.TAKEN })
    }

    @Test
    fun m2_markOthersNotChosenMarksTheCloseTargetsAndDeletesNothing(): Unit = runBlocking {
        db.houses().upsert(house("h1", HouseStatus.TAKEN))
        db.houses().upsert(house("h2", HouseStatus.NEW))
        db.houses().upsert(house("h3", HouseStatus.REJECTED))
        db.houses().upsert(house("h4", HouseStatus.SHORTLISTED))
        assertEquals(2, repo.closeTargetCount("h1"))
        assertEquals(2, repo.markOthersNotChosen("h1"))
        assertEquals(
            listOf(HouseStatus.TAKEN, HouseStatus.NOT_CHOSEN, HouseStatus.REJECTED, HouseStatus.NOT_CHOSEN),
            listOf("h1", "h2", "h3", "h4").map { db.houses().get(it)!!.status },
        )
        assertEquals(4, db.houses().all().size)
        assertEquals(0, repo.markOthersNotChosen("h1"))
    }

    @Test
    fun anEditStampsTheMetaAndMarksItOnlyWhenSomethingChanged(): Unit = runBlocking {
        db.photos().upsert(PhotoEntity("p1", "h1", "/x", uploaded = true, createdAt = at))
        assertTrue(repo.savePhotoMeta("p1", PhotoMeta("r1", listOf("move_in", "leak"), "Tap", 0)))
        val p = db.photos().get("p1")!!
        assertEquals(PhotoMeta("r1", listOf("MOVE_IN", "LEAK"), "Tap", p.metaUpdatedAt), p.meta)
        assertTrue(p.metaUpdatedAt > 0 && p.metaDirty)
        assertFalse("the same values write nothing", repo.savePhotoMeta("p1", PhotoMeta("r1", listOf("MOVE_IN", "LEAK"), "Tap")))
        assertFalse("no such photo", repo.savePhotoMeta("zz", PhotoMeta(caption = "x")))
    }

    @Test
    fun syncSendsTheMetaAfterTheUploadAndClearsTheFlag(): Unit = runBlocking {
        repo.photoFile("p1").writeBytes(byteArrayOf(1, 2, 3))
        db.photos().upsert(PhotoEntity("p1", "h1", "/x", uploaded = false, createdAt = at).withMeta(PhotoMeta(tags = listOf("MOVE_IN"), metaUpdatedAt = 50), dirty = true))
        repo.sync(photosAllowed = true)
        val upload = server.seen.indexOf("POST /api/houses/h1/photos")
        val meta = server.seen.indexOf("PUT /api/photos/p1/meta")
        assertTrue(server.seen.toString(), upload >= 0 && meta > upload)
        assertTrue(server.bodies.any { it == "{\"tags\":[\"MOVE_IN\"],\"metaUpdatedAt\":50}" || it.contains("\"tags\":[\"MOVE_IN\"]") && it.contains("\"metaUpdatedAt\":50") })
        assertFalse(db.photos().get("p1")!!.metaDirty)
    }

    @Test
    fun aNewerMetaOnTheServerWinsOverTheEditSentAndA404ClearsTheFlag(): Unit = runBlocking {
        db.photos().upsert(PhotoEntity("p1", "h1", "/x", uploaded = true, createdAt = at).withMeta(PhotoMeta(caption = "Mine", metaUpdatedAt = 50), dirty = true))
        server.metaAnswer = "{\"id\":\"p1\",\"houseId\":\"h1\",\"caption\":\"Theirs\",\"tags\":[\"LEAK\"],\"metaUpdatedAt\":60}"
        repo.sync(photosAllowed = true)
        val p = db.photos().get("p1")!!
        assertEquals(PhotoMeta(null, listOf("LEAK"), "Theirs", 60), p.meta)
        assertFalse(p.metaDirty)

        db.photos().upsert(p.withMeta(PhotoMeta(caption = "Again", metaUpdatedAt = 70), dirty = true))
        server.metaAnswer = null
        server.metaStatus = HttpStatusCode.NotFound
        repo.sync(photosAllowed = true)
        assertFalse("a photo the server does not have is not retried forever", db.photos().get("p1")!!.metaDirty)
        assertEquals("Again", db.photos().get("p1")!!.caption)
    }

    @Test
    fun aPulledMetaChangeWinsOnlyWhenItIsNewer(): Unit = runBlocking {
        db.photos().upsert(PhotoEntity("p1", "h1", "/x", uploaded = true, createdAt = at).withMeta(PhotoMeta(caption = "Here", metaUpdatedAt = 50), dirty = false))
        server.photoChanges = "[{\"id\":\"p1\",\"houseId\":\"h1\",\"caption\":\"Old\",\"metaUpdatedAt\":40,\"syncVersion\":3}]"
        repo.sync(photosAllowed = true)
        assertEquals("Here", db.photos().get("p1")!!.caption)
        server.photoChanges = "[{\"id\":\"p1\",\"houseId\":\"h1\",\"roomId\":\"r9\",\"caption\":\"New\",\"metaUpdatedAt\":90,\"syncVersion\":4}]"
        settings.savePhotoCursor(0)
        repo.sync(photosAllowed = true)
        val p = db.photos().get("p1")!!
        assertEquals(PhotoMeta("r9", emptyList(), "New", 90), p.meta)
        assertFalse(p.metaDirty)
    }

    @Test
    fun anImportWritesANewerFileMetaOntoAPhotoHereAndAnOlderOneChangesNothing(): Unit = runBlocking {
        db.photos().upsert(PhotoEntity("p1", "h1", "/x", uploaded = true, createdAt = at).withMeta(PhotoMeta(caption = "Here", metaUpdatedAt = 50), dirty = false))
        val newer = ExportPhoto("p1", "h1", "p1.jpg", at, caption = "From the file", tags = listOf("VIEW"), metaUpdatedAt = 80)
        fun actions(p: ExportPhoto) = ImportActions(ImportMode.MERGE, emptyList(), emptyList(), emptyList(), emptyMap(), photoMeta = listOf(p))
        repo.applyImport(actions(newer.copy(metaUpdatedAt = 40, caption = "Older")), { _, _ -> }) { null }
        assertEquals("Here", db.photos().get("p1")!!.caption)
        repo.applyImport(actions(newer), { _, _ -> }) { null }
        val p = db.photos().get("p1")!!
        assertEquals(PhotoMeta(null, listOf("VIEW"), "From the file", 80), p.meta)
        assertTrue("sent on the next sync", p.metaDirty)
        assertEquals(mapOf("p1" to 80L), repo.localVersions().photoMeta)
    }
}
