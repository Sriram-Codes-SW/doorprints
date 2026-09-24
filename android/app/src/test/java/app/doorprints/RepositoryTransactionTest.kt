package app.doorprints

import android.app.Application
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.doorprints.data.AndroidRepository
import app.doorprints.data.AppDatabase
import app.doorprints.data.DatabaseFile
import app.doorprints.data.HouseEntity
import app.doorprints.data.PhotoEntity
import app.doorprints.data.Repository
import app.doorprints.data.SecretStore
import app.doorprints.data.SettingsStore
import app.doorprints.data.VisitEntity
import app.doorprints.data.create
import app.doorprints.data.withImmediateTransaction
import app.doorprints.shared.export.ExportHouse
import app.doorprints.shared.export.ExportPhoto
import app.doorprints.shared.export.ExportVisit
import app.doorprints.shared.export.ImportActions
import app.doorprints.shared.export.ImportMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The `Repository`'s transactions and change flow on Room's common API (CMP-4 P4c, S4b-BL-23; docs/06 TC-U-66), run
 * on the app's own database: [AppDatabase.create] with no driver, so the framework SQLite and Room's compatibility
 * mode, as on a phone. A copy import and [withImmediateTransaction] commit everything or nothing, the DAO calls inside
 * join the one transaction, and `localRowsFlow` emits again after each change to the houses, visits or photos table
 * and after a transaction commits.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: DoorprintsApp would start MapLibre (native code) and its own WorkManager.
@Config(sdk = [35], application = Application::class)
class RepositoryTransactionTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var repo: AndroidRepository

    /** Nothing here reads the API key. */
    private object NoSecrets : SecretStore {
        override fun get(settings: Preferences): String? = null
        override fun put(settings: MutablePreferences, apiKey: String) = Unit
        override fun clear(settings: MutablePreferences) = Unit
    }

    /** What a test throws to stop a write half-way, as the Import screen's Stop does. */
    private class Stop : RuntimeException("stopped")

    @Before
    fun setUp() {
        // "Sync soon" goes to a test WorkManager; its network constraint keeps the job queued, so no sync runs.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        context.deleteDatabase(DatabaseFile.NAME)
        db = AppDatabase.create(context)
        val settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "test.preferences_pb") },
            NoSecrets,
        )
        repo = AndroidRepository(context, db, settings)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(DatabaseFile.NAME)
    }

    private fun house(id: String, at: Long = 1_760_000_000_000) =
        HouseEntity(id = id, label = "House $id", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at)

    private fun visit(id: String, houseId: String?, at: Long = 1_760_000_000_000) =
        VisitEntity(id = id, houseId = houseId, lat = 12.97, lon = 77.59, arrivedAt = at, updatedAt = at)

    /** A copy of two houses, a visit of the first and one photo of it (ids as `ImportPlan` would give them). */
    private fun copyActions(): ImportActions {
        val at = 1_760_000_000_000
        return ImportActions(
            mode = ImportMode.COPY,
            houses = listOf("c1", "c2").map { ExportHouse(id = it, label = "Copy $it", lat = 1.0, lon = 2.0, createdAt = at, updatedAt = at) },
            visits = listOf(ExportVisit(id = "cv1", houseId = "c1", lat = 1.0, lon = 2.0, arrivedAt = at, updatedAt = at)),
            photos = listOf(ExportPhoto(id = "cp1", houseId = "c1", fileName = "cp1.jpg", createdAt = at)),
            photoSources = mapOf("cp1" to "photos/cp1.jpg"),
        )
    }

    private val photoBytes: suspend (String) -> ByteArray? = { byteArrayOf(1, 2, 3) }

    private fun ids(rows: Repository.LocalRows) =
        Triple(rows.houses.map { it.id }.toSet(), rows.visits.map { it.id }.toSet(), rows.photos.map { it.id }.toSet())

    @Test
    fun aCopyStoppedInsideItsTransactionLeavesThePhoneAsItWas() = runBlocking {
        repo.saveHouse(house("h0"))
        val before = ids(repo.localRows())
        // Progress: the photo file first (1), then house c1 (2), house c2 (3), visit cv1 (4). Stop at 3: both house
        // rows are written inside the transaction by then, the visit is not.
        try {
            repo.applyImport(copyActions(), onProgress = { done, _ -> if (done == 3) throw Stop() }, photoBytes = photoBytes)
            fail("the copy should have stopped")
        } catch (_: Stop) {
        }
        assertEquals(before, ids(repo.localRows()))
        assertNull(db.houses().get("c1"))
        assertNull(db.photos().get("cp1"))
        // The photo file written before the transaction was deleted after the rollback.
        assertFalse(repo.photoFile("cp1").exists())
    }

    @Test
    fun aCopyWhoseCallerIsCancelledInsideItsTransactionLeavesThePhoneAsItWas() = runBlocking {
        repo.saveHouse(house("h0"))
        val before = ids(repo.localRows())
        // A real cancellation, not a thrown exception: the caller's job is cancelled once both house rows are written
        // inside the transaction (progress 3). Room runs the block outside the caller's Job, so the block runs to its
        // end; then withImmediateTransaction's ensureActive() throws before the commit and Room rolls back.
        lateinit var job: Job
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            repo.applyImport(copyActions(), onProgress = { done, _ -> if (done == 3) job.cancel() }, photoBytes = photoBytes)
        }
        job.start() // started only once `job` is assigned, so the progress callback can cancel it
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(before, ids(repo.localRows()))
        assertNull(db.houses().get("c1"))
        assertNull(db.photos().get("cp1"))
        // The photo file written before the transaction was deleted after the rollback.
        assertFalse(repo.photoFile("cp1").exists())
    }

    /**
     * The copy's cleanup after a cancellation that lands after the commit (code review of PR #23): the transaction has
     * committed, then the caller's resumption throws, and the catch block cleans up. It must keep a file whose photo
     * row was committed and delete only one whose row is not there, even though the coroutine running it is cancelled.
     * The commit and the late cancellation cannot be ordered from a test (Room resumes the caller on its own), so the
     * cleanup is run as the catch block runs it: in a cancelled coroutine, over the files of a committed copy and one
     * whose row never made it.
     */
    @Test
    fun aCancellationAfterTheCommitKeepsThePhotoFilesOfCommittedRows() = runBlocking {
        repo.applyImport(copyActions(), photoBytes = photoBytes)
        val committed = repo.photoFile("cp1")
        assertTrue(committed.exists())
        val orphan = repo.photoFile("cp2").apply { writeBytes(byteArrayOf(4, 5, 6)) }

        // Started at once (UNDISPATCHED), so it is waiting in the try when it is cancelled, not cancelled before it ran.
        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                // Cancelled here, as the copy's catch block is after a late cancellation.
                repo.discardUncommittedPhotoFiles(mapOf(committed to "cp1", orphan to "cp2"))
            }
        }
        job.cancelAndJoin()

        assertTrue("the committed photo keeps its file", committed.exists())
        assertEquals("cp1", db.photos().get("cp1")?.id)
        assertFalse("a file without a row is deleted", orphan.exists())
    }

    @Test
    fun aCopyThatFinishesCommitsEveryRow() = runBlocking {
        val result = repo.applyImport(copyActions(), photoBytes = photoBytes)
        assertEquals(Triple(2, 1, 1), Triple(result.houses, result.visits, result.photos))
        assertEquals(Triple(setOf("c1", "c2"), setOf("cv1"), setOf("cp1")), ids(repo.localRows()))
        assertTrue(repo.photoFile("cp1").exists())
        assertEquals(setOf("c1", "c2"), result.copiedHouses.keys)
    }

    @Test
    fun aFailedTransactionRollsBackEveryDaoCallInsideIt() = runBlocking {
        try {
            db.withImmediateTransaction {
                db.houses().upsert(house("t1"))
                db.visits().upsert(visit("tv1", "t1"))
                // The DAO calls joined this transaction: their rows are visible inside it.
                assertEquals("t1", db.houses().get("t1")?.id)
                // A nested transaction joins the outer one too (as withTransaction's did): rolled back with it.
                db.withImmediateTransaction { db.photos().upsert(PhotoEntity("tp1", "t1", "/x.jpg", false, 1L)) }
                throw Stop()
            }
        } catch (_: Stop) {
        }
        assertNull(db.houses().get("t1"))
        assertNull(db.visits().get("tv1"))
        assertNull(db.photos().get("tp1"))
        // The writer connection is free again: the next transaction commits.
        db.withImmediateTransaction { db.houses().upsert(house("t2")) }
        assertEquals("t2", db.houses().get("t2")?.id)
    }

    @Test
    fun theLocalRowsFlowEmitsAfterEveryTableChangeAndEveryCommit() = runBlocking {
        val seen = Channel<Repository.LocalRows>(Channel.UNLIMITED)
        val collector = scope.launch { repo.localRowsFlow().collect { seen.send(it) } }

        /** Reads emissions until one has [houses], [visits] and [photos]; fails after 10 s. */
        suspend fun awaitRows(houses: Set<String>, visits: Set<String>, photos: Set<String>) = withTimeout(10_000) {
            while (ids(seen.receive()) != Triple(houses, visits, photos)) Unit
        }

        try {
            awaitRows(emptySet(), emptySet(), emptySet())
            repo.saveHouse(house("h1"))
            awaitRows(setOf("h1"), emptySet(), emptySet())
            repo.saveVisit(visit("v1", "h1"))
            awaitRows(setOf("h1"), setOf("v1"), emptySet())
            db.photos().upsert(PhotoEntity("p1", "h1", "/p1.jpg", false, 1L))
            awaitRows(setOf("h1"), setOf("v1"), setOf("p1"))
            // A copy writes its rows in one transaction: the flow sees them after the commit.
            repo.applyImport(copyActions(), photoBytes = photoBytes)
            awaitRows(setOf("h1", "c1", "c2"), setOf("v1", "cv1"), setOf("p1", "cp1"))
            // A house turned into a tombstone leaves the copy's rows.
            repo.deleteHouse("c2")
            awaitRows(setOf("h1", "c1"), setOf("v1", "cv1"), setOf("p1", "cp1"))
        } finally {
            collector.cancel()
        }
    }
}
