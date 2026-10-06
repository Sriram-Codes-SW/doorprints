package app.doorprints.drive.store

import app.doorprints.crypto.KeysWatermark
import app.doorprints.drive.backup.ControlWatermark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The two watermarks of a folder: the pin of `keys.json` and the mark of `doorprints.json`. The contract (KeysGuard,
 * ControlFile): compareAndSet stores `next` only when the stored value equals `expected` (null: nothing stored), atomically.
 * Web twin: `drive-db.ts` and `keys-reload.spec.ts`.
 */
class FolderTrustStoresTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun keysWm(epoch: Int, rev: Long, id: Byte = 1, hash: Byte = 2) =
        KeysWatermark(epoch, rev, ByteArray(16) { id }, ByteArray(32) { hash })

    private fun controlWm(rev: Long, hash: Byte = 3, deleted: Long? = null) = ControlWatermark(rev, ByteArray(32) { hash }, deleted)

    private fun stores() = FileFolderTrustStores(tmp.root)

    @Test
    fun nothingStoredLoadsNull() {
        assertNull(stores().keys("root1").load())
        assertNull(stores().control("root1").load())
    }

    @Test
    fun keysCompareAndSetFollowsTheContract() {
        val s = stores().keys("root1")
        val a = keysWm(1, 1)
        val b = keysWm(1, 2)
        assertTrue(s.compareAndSet(null, a))
        assertEquals(a, s.load())
        assertFalse("expected null but a is stored", s.compareAndSet(null, b))
        assertEquals(a, s.load())
        assertFalse("expected a stale value", s.compareAndSet(keysWm(1, 9), b))
        assertEquals(a, s.load())
        assertTrue(s.compareAndSet(a, b))
        assertEquals(b, s.load())
    }

    @Test
    fun keysCompareAndSetComparesEveryPartOfTheWatermark() {
        val s = stores().keys("root1")
        val a = keysWm(1, 1)
        s.compareAndSet(null, a)
        val next = keysWm(2, 1)
        assertFalse(s.compareAndSet(keysWm(2, 1), next))
        assertFalse(s.compareAndSet(keysWm(1, 2), next))
        assertFalse(s.compareAndSet(keysWm(1, 1, id = 9), next))
        assertFalse(s.compareAndSet(keysWm(1, 1, hash = 9), next))
        assertEquals(a, s.load())
        assertTrue(s.compareAndSet(keysWm(1, 1), next))
    }

    @Test
    fun controlCompareAndSetFollowsTheContract() {
        val s = stores().control("root1")
        val a = controlWm(1)
        val b = controlWm(2, deleted = 77)
        assertTrue(s.compareAndSet(null, a))
        assertFalse(s.compareAndSet(null, b))
        assertFalse(s.compareAndSet(controlWm(1, hash = 9), b))
        assertFalse(s.compareAndSet(controlWm(1, deleted = 5), b))
        assertEquals(a, s.load())
        assertTrue(s.compareAndSet(a, b))
        assertEquals(b, s.load())
        assertEquals(77L, s.load()!!.backupsDeletedAt)
    }

    @Test
    fun aNewInstanceOnTheSameFilesSeesTheSamePins() {
        val k = keysWm(3, 17, id = 7, hash = 8)
        val c = controlWm(4, hash = 6, deleted = 9)
        stores().keys("root1").compareAndSet(null, k)
        stores().control("root1").compareAndSet(null, c)
        // As after the app restarted: nothing is shared but the files.
        assertEquals(k, FileFolderTrustStores(tmp.root).keys("root1").load())
        assertEquals(c, FileFolderTrustStores(tmp.root).control("root1").load())
        assertEquals(3, FileFolderTrustStores(tmp.root).keys("root1").load()!!.epoch)
    }

    @Test
    fun aNewInstanceStillRefusesAStaleExpectation() {
        val a = keysWm(1, 1)
        stores().keys("root1").compareAndSet(null, a)
        assertFalse(FileFolderTrustStores(tmp.root).keys("root1").compareAndSet(null, keysWm(1, 5)))
    }

    @Test
    fun rootsKeysAndControlDoNotShareState() {
        stores().keys("rootA").compareAndSet(null, keysWm(1, 1))
        assertNull(stores().keys("rootB").load())
        assertNull(stores().control("rootA").load())
        stores().control("rootA").compareAndSet(null, controlWm(1))
        assertEquals(keysWm(1, 1), stores().keys("rootA").load())
    }

    @Test
    fun aCorruptFileReadsAsNoPinWithoutCrashing() {
        stores().keys("root1").compareAndSet(null, keysWm(1, 1))
        stores().control("root1").compareAndSet(null, controlWm(1))
        for (f in tmp.root.walkTopDown().filter { it.isFile }) f.writeText("{\"v\":1,\"epoch\":")
        assertNull(stores().keys("root1").load())
        assertNull(stores().control("root1").load())
        // And a pin can be set again over it.
        assertTrue(stores().keys("root1").compareAndSet(null, keysWm(2, 1)))
        assertEquals(keysWm(2, 1), stores().keys("root1").load())
    }

    @Test
    fun aWatermarkWithABrokenHashReadsAsNoPin() {
        stores().keys("root1").compareAndSet(null, keysWm(1, 1))
        val f = tmp.root.walkTopDown().single { it.isFile }
        f.writeText(f.readText().replace(Regex("\"keyId\":\"[0-9a-f]+\""), "\"keyId\":\"zz\""))
        assertNull(stores().keys("root1").load())
    }

    @Test
    fun aRootIdCannotLeaveTheFolder() {
        for (evil in listOf("../escape", "a/b", "..", "/abs", "x\u0000y", "A".repeat(500))) {
            stores().keys(evil).compareAndSet(null, keysWm(1, 1))
            stores().control(evil).compareAndSet(null, controlWm(1))
            assertNotNull(stores().keys(evil).load())
        }
        assertEquals(emptyList<File>(), tmp.root.parentFile!!.listFiles()!!.filter { it.name.contains("escape") })
        assertTrue(tmp.root.walkTopDown().filter { it.isFile }.all { it.parentFile == tmp.root })
    }

    @Test
    fun differentRootIdsNeverCollide() {
        stores().keys("ab").compareAndSet(null, keysWm(1, 1))
        assertNull(stores().keys("AB").load())
        assertNull(stores().keys("x61x62").load())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBlankRootIdIsRefused() {
        stores().keys(" ")
    }

    @Test
    fun onlyOneOfManyConcurrentFirstPinsWins() {
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val wins = AtomicInteger()
        val tasks = (1..threads).map { i ->
            pool.submit {
                // Half the writers use their own instance on the same file.
                val store = FileFolderTrustStores(tmp.root).keys("root1")
                start.await()
                if (store.compareAndSet(null, keysWm(1, i.toLong()))) wins.incrementAndGet()
            }
        }
        start.countDown()
        tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, wins.get())
        assertNotNull(stores().keys("root1").load())
    }

    @Test
    fun concurrentReadModifyWriteLosesNoUpdate() {
        val threads = 8
        val each = 25
        val pool = Executors.newFixedThreadPool(threads)
        stores().control("root1").compareAndSet(null, controlWm(0))
        val tasks = (1..threads).map {
            pool.submit {
                val store = FileFolderTrustStores(tmp.root).control("root1")
                repeat(each) {
                    while (true) {
                        val cur = store.load()!!
                        if (store.compareAndSet(cur, controlWm(cur.revision + 1))) break
                    }
                }
            }
        }
        tasks.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals((threads * each).toLong(), stores().control("root1").load()!!.revision)
    }

    @Test
    fun theBundleGivesStoresOnTheSameFiles() {
        val dir = File(tmp.root, "bundle")
        DriveFileStores(dir).trust.keys("r").compareAndSet(null, keysWm(1, 1))
        assertEquals(keysWm(1, 1), DriveFileStores(dir).trust.keys("r").load())
    }
}
