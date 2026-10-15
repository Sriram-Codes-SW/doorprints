package app.doorprints.shared.voice.pcm

import app.doorprints.shared.voice.AudioLimits
import app.doorprints.shared.voice.PcmSource
import app.doorprints.shared.voice.PcmVoiceRecorder
import app.doorprints.shared.voice.VoiceLimit
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tail of a recording on the iPhone (S4b-BL-223, TC-U-254): the person's Stop must not lose what the source already
 * holds. A source with `IosPcmSource`'s rules (a blocking `read`, `end()` keeps the queue and hands it out, `close()`
 * discards it) is run by the shared [PcmVoiceRecorder] on a real thread, with bytes arriving after the loop is already
 * waiting and a slow reader, and the clip must hold every one of them. Host (JVM) test: it needs threads.
 */
class IosCaptureDrainTest {
    private class DrainSource(private val readDelayMs: Long = 0) : PcmSource {
        private val lock = Object()
        private val queue = ByteQueue(8 * 1024 * 1024)
        private var ended = false
        var closed = 0
        var ends = 0

        fun push(samples: Int, value: Short) = synchronized(lock) {
            repeat(samples) { queue.write(value) }
            lock.notifyAll()
        }

        override fun open() = Unit

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            val n = synchronized(lock) {
                while (queue.available == 0 && !ended) lock.wait()
                if (queue.available == 0) return -1
                queue.read(into, offset, length)
            }
            if (readDelayMs > 0) Thread.sleep(readDelayMs) // a loaded phone: the loop is late with what it has just taken
            return n
        }

        override fun end(): Boolean = synchronized(lock) {
            ends++
            ended = true
            lock.notifyAll()
            true
        }

        override fun close() {
            synchronized(lock) {
                closed++
                ended = true
                queue.clear()
                lock.notifyAll()
            }
        }
    }

    private fun data(clipBytes: ByteArray) = clipBytes.copyOfRange(44, clipBytes.size)

    @Test
    fun bytesQueuedJustBeforeStopAreInTheClip() {
        val source = DrainSource()
        val r = PcmVoiceRecorder(source, Dispatchers.Default)
        r.start()
        source.push(1_600, 0x1234) // the loop is already waiting in read
        source.push(1_600, 0x1234)
        val clip = r.stop()
        assertEquals(3_200 * 2, data(clip.bytes).size, "every queued byte is in the clip")
        assertTrue(data(clip.bytes).toList().chunked(2).all { it[0] == 0x34.toByte() && it[1] == 0x12.toByte() })
        assertNull(clip.limit)
        assertEquals(1, source.ends)
        assertEquals(1, source.closed, "the microphone is let go after the drain")
    }

    @Test
    fun aSlowReaderStillDeliversTheTailBeforeStopReturns() {
        val source = DrainSource(readDelayMs = 60)
        val r = PcmVoiceRecorder(source, Dispatchers.Default)
        r.start()
        source.push(3_000, 7)
        Thread.sleep(20) // the loop has taken the first chunk and is late with it
        source.push(2_000, 7)
        val clip = r.stop()
        assertEquals(5_000 * 2, data(clip.bytes).size)
    }

    @Test
    fun theCapStillHoldsWhenStopDrainsALotOfAudio() {
        val source = DrainSource()
        val r = PcmVoiceRecorder(source, Dispatchers.Default)
        r.start()
        source.push(AudioLimits.MAX_BYTES / 2 + 50_000, 5) // more than the cap in bytes
        val clip = r.stop()
        assertTrue(clip.bytes.size <= AudioLimits.MAX_BYTES, "${clip.bytes.size}")
        assertEquals(VoiceLimit.SIZE, clip.limit)
        assertEquals(1, source.closed)
    }

    @Test
    fun releaseDiscardsWhatIsQueued() {
        val source = DrainSource()
        val r = PcmVoiceRecorder(source, Dispatchers.Default)
        r.start()
        source.push(1_000, 9)
        r.release()
        assertEquals(0, source.ends, "a discard does not drain")
        assertEquals(1, source.closed)
        assertEquals(-1, source.read(ByteArray(16), 0, 16), "nothing is left to read")
    }
}
