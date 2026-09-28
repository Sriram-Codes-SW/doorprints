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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * S4b-BL-52 on the app's own database (Robolectric) against a fake server (Ktor's MockEngine, as in
 * `SyncServerResetTest`): a photo row whose stored path names a folder that has moved (an iOS app's container after
 * an update) while its bytes are in the current photo folder is still uploaded by sync, still deleted by a server
 * tombstone, and its file is still removed when the copy that brought it is undone.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: DoorprintsApp would start MapLibre (native code) and its own WorkManager.
@Config(sdk = [35], application = Application::class)
class PhotoStalePathSyncTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: AndroidRepository

    /** The key, in memory, made at run time (no key-like literal in the source). */
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

    /** The fake server: `GET /api/photos` answers [photoChanges]; every request it saw ("POST /api/houses/h1/photos"). */
    private class Server {
        var photoChanges = "[]"
        val seen = mutableListOf<String>()

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val method = request.method.value
            seen += "$method $path"
            val body = when {
                path == "/api/stats" -> "{\"houses\":1,\"shortlisted\":0,\"rejected\":0,\"visits\":0,\"streets\":0}"
                method == "GET" && path == "/api/photos" -> photoChanges
                method == "POST" -> "{}"
                else -> "[]"
            }
            respond(body, HttpStatusCode.OK, Headers.build { append("Content-Type", "application/json") })
        }
    }

    private val server = Server()
    private val at = 1_760_000_000_000

    /** A path in a folder that is not the current photo folder, as a row written before an iOS update names it. */
    private fun stalePath(id: String) = File(context.cacheDir, "old/photos/$id.jpg").path

    @Before
    fun setUp(): Unit = runBlocking {
        // "Sync soon" goes to a test WorkManager; its network constraint keeps the job queued, so no sync runs.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        context.deleteDatabase(DatabaseFile.NAME)
        db = AppDatabase.create(context)
        settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "stale-test.preferences_pb") },
            MemorySecrets,
        )
        settings.saveServer("https://sync.example", "test-" + UUID.randomUUID())
        repo = AndroidRepository(context, db, settings) { url, key ->
            ApiClient(url, key, ApiHttp.client(server.engine), callTimeoutMs = null)
        }
        db.houses().upsert(
            HouseEntity(id = "h1", label = "Synced house", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at, dirty = false)
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(DatabaseFile.NAME)
        File(context.filesDir, "stale-test.preferences_pb").delete()
        repo.photoFile("p9").delete()
    }

    @Test
    fun aStalePathRowIsUploadedFromTheCurrentFolder(): Unit = runBlocking {
        repo.photoFile("p9").writeBytes(byteArrayOf(1, 2, 3))
        db.photos().upsert(PhotoEntity("p9", "h1", stalePath("p9"), uploaded = false, createdAt = at))
        assertFalse("the stored path is stale", File(stalePath("p9")).exists())

        repo.sync(photosAllowed = true)

        assertTrue(server.seen.contains("POST /api/houses/h1/photos"))
        assertEquals(true, db.photos().get("p9")?.uploaded)
        assertTrue(db.photos().pendingUpload().isEmpty())
    }

    @Test
    fun aServerTombstoneDeletesTheCurrentFolderFileOfAStalePathRow(): Unit = runBlocking {
        val file = repo.photoFile("p9").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        db.photos().upsert(PhotoEntity("p9", "h1", stalePath("p9"), uploaded = true, createdAt = at))
        server.photoChanges = "[{\"id\":\"p9\",\"houseId\":\"h1\",\"deleted\":true,\"syncVersion\":5}]"

        repo.sync(photosAllowed = true)

        assertFalse("the file in the current folder is gone", file.exists())
        assertNull(db.photos().get("p9"))
    }

    @Test
    fun undoingACopyRemovesTheCurrentFolderFileOfAStalePathRow(): Unit = runBlocking {
        // A house the copy brought, unchanged since, with its one photo.
        db.houses().upsert(HouseEntity(id = "h2", label = "Copied house", lat = 12.9, lon = 77.6, createdAt = at, updatedAt = at))
        val file = repo.photoFile("p9").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        db.photos().upsert(PhotoEntity("p9", "h2", stalePath("p9"), uploaded = false, createdAt = at))

        val result = repo.undoCopyImport(houses = mapOf("h2" to at), visits = emptyMap(), photos = listOf("p9"))

        assertEquals(1, result.removed)
        assertFalse("the file in the current folder is gone", file.exists())
        assertNull(db.photos().get("p9"))
    }
}
