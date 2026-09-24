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
import app.doorprints.data.VisitEntity
import app.doorprints.data.create
import app.doorprints.shared.api.ApiClient
import app.doorprints.shared.api.ApiHttp
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.sync.SyncOutcome
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * S4b-BL-20: the repository's sync finds a server that went back (a new, empty database, or a restore from an older
 * dump) and sends everything again: from `GET /api/stats`' maxSyncVersion below a stored cursor, or, from a server
 * without that field, from a push answered with a version at or below one (as the web does). Every house and visit is
 * pushed again, every photo uploaded again, the pull starts from 0 and the outcome says so. A healthy server, an older
 * one that says nothing, and a push that last-write-wins answered with the server's newer row change nothing. Run on
 * the app's own database (Robolectric) against a fake server (Ktor's MockEngine).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: DoorprintsApp would start MapLibre (native code) and its own WorkManager.
@Config(sdk = [35], application = Application::class)
class SyncServerResetTest {
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

    /** The fake server: its answers, and every request it saw ("GET /api/houses?since=0", "PUT /api/houses/h1"). */
    private class Server {
        /** `maxSyncVersion` in `GET /api/stats`; null leaves the field out, as an older server does. */
        var maxSyncVersion: Long? = null

        /** The version a PUT is answered with. */
        var putVersion = 1000L

        /** Added to the sent `updatedAt` in a PUT's answer: above 0, last-write-wins kept the server's newer row. */
        var answerLaterByMs = 0L
        val seen = mutableListOf<String>()

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val query = request.url.encodedQuery
            val method = request.method.value
            seen += if (query.isEmpty()) "$method $path" else "$method $path?$query"
            val body = when {
                path == "/api/stats" -> "{\"houses\":1,\"shortlisted\":0,\"rejected\":0,\"visits\":1,\"streets\":0" +
                    (maxSyncVersion?.let { ",\"maxSyncVersion\":$it" } ?: "") + "}"
                method == "PUT" -> answer(request.body.toByteArray().decodeToString())
                method == "POST" -> "{}"
                else -> "[]"
            }
            respond(body, HttpStatusCode.OK, Headers.build { append("Content-Type", "application/json") })
        }

        /** The stored row, as the backend answers a PUT: the body with its version and, maybe, a later time. */
        private fun answer(sent: String): String {
            val time = Regex("\"updatedAt\":\"([^\"]+)\"").find(sent)!!.groupValues[1]
            val stored = IsoTime.format(IsoTime.parseMillis(time) + answerLaterByMs)
            return sent.replace(Regex(",?\"syncVersion\":\\d+"), "")
                .replace("\"updatedAt\":\"$time\"", "\"updatedAt\":\"$stored\"")
                .dropLast(1) + ",\"syncVersion\":$putVersion}"
        }
    }

    private val server = Server()
    private val at = 1_760_000_000_000

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
            PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "sync-test.preferences_pb") },
            MemorySecrets,
        )
        settings.saveServer("https://sync.example", "test-" + UUID.randomUUID())
        repo = AndroidRepository(context, db, settings) { url, key ->
            ApiClient(url, key, ApiHttp.client(server.engine), callTimeoutMs = null)
        }
        // What an earlier sync left: one house, one visit and one photo, all on the server, and the cursors.
        db.houses().upsert(HouseEntity(id = "h1", label = "Synced house", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at, dirty = false))
        db.visits().upsert(VisitEntity(id = "v1", houseId = "h1", lat = 12.97, lon = 77.59, arrivedAt = at, updatedAt = at, dirty = false))
        val photo = repo.photoFile("p1").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        db.photos().upsert(PhotoEntity("p1", "h1", photo.absolutePath, uploaded = true, createdAt = at))
        settings.saveCursors(house = 250, visit = 240)
        settings.savePhotoCursor(230)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(DatabaseFile.NAME)
        File(context.filesDir, "sync-test.preferences_pb").delete()
    }

    private fun sync(): SyncOutcome = runBlocking { repo.sync(photosAllowed = true) }

    private fun pulls() = server.seen.filter { it.startsWith("GET /api/") && it.contains("since=") }

    private fun assertEverythingSentAgainAndPulledFromZero(outcome: SyncOutcome) {
        assertTrue(outcome.serverReset)
        assertTrue(server.seen.contains("PUT /api/houses/h1"))
        assertTrue(server.seen.contains("PUT /api/visits/v1"))
        assertTrue(server.seen.contains("POST /api/houses/h1/photos"))
        assertEquals(listOf("GET /api/houses?since=0", "GET /api/visits?since=0", "GET /api/photos?since=0"), pulls())
        runBlocking {
            assertEquals(SettingsStore.Cursors(0, 0, 0), settings.cursors())
            assertTrue(db.houses().dirty().isEmpty())
            assertTrue(db.visits().dirty().isEmpty())
            assertTrue(db.photos().pendingUpload().isEmpty())
        }
    }

    @Test
    fun aServerBehindTheCursorsGetsEverythingAgainAndIsPulledFromZero() {
        server.maxSyncVersion = 5 // Restored from a dump older than this phone's last sync.
        val outcome = sync()
        assertEverythingSentAgainAndPulledFromZero(outcome)
        assertEquals(3, outcome.pushed)
        assertEquals("GET /api/stats", server.seen.first())
    }

    @Test
    fun aHealthyServerIsLeftAsItIs() {
        server.maxSyncVersion = 250 // At the highest cursor: nothing lost.
        val outcome = sync()
        assertFalse(outcome.serverReset)
        assertEquals(0, outcome.pushed)
        assertEquals(listOf("GET /api/houses?since=250", "GET /api/visits?since=240", "GET /api/photos?since=230"), pulls())
    }

    @Test
    fun anOlderServerWithoutTheFieldIsLeftAsItIs() {
        server.maxSyncVersion = null
        val outcome = sync()
        assertFalse(outcome.serverReset)
        assertEquals(listOf("GET /api/houses?since=250", "GET /api/visits?since=240", "GET /api/photos?since=230"), pulls())
    }

    @Test
    fun aPushAnsweredBelowTheCursorsShowsTheResetOnAnOlderServer(): Unit = runBlocking {
        server.maxSyncVersion = null
        server.putVersion = 7 // A new, empty database hands out small versions again.
        db.houses().upsert(HouseEntity(id = "h2", label = "New house", lat = 12.9, lon = 77.6, createdAt = at + 5, updatedAt = at + 5))
        val outcome = sync()
        assertEverythingSentAgainAndPulledFromZero(outcome)
        assertTrue(server.seen.contains("PUT /api/houses/h2"))
        // h2 went twice (the first answer showed the reset before it counted); h1, h2, v1 and p1 were pushed.
        assertEquals(2, server.seen.count { it == "PUT /api/houses/h2" })
        assertEquals(4, outcome.pushed)
    }

    @Test
    fun aPushThatKeptTheServersNewerRowIsNotAReset(): Unit = runBlocking {
        server.maxSyncVersion = null
        server.putVersion = 7
        server.answerLaterByMs = 60_000 // Last-write-wins kept the server's row: its old version, a later time.
        db.houses().upsert(HouseEntity(id = "h2", label = "Edited", lat = 12.9, lon = 77.6, createdAt = at, updatedAt = at + 5))
        val outcome = sync()
        assertFalse(outcome.serverReset)
        assertEquals(1, server.seen.count { it.startsWith("PUT ") })
        assertEquals(listOf("GET /api/houses?since=250", "GET /api/visits?since=240", "GET /api/photos?since=230"), pulls())
    }
}
