package app.doorprints.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A thin, streaming ZIP writer. Everything is written straight to the destination stream (the user's SAF file),
 * so a backup with 500 photos never has to fit in memory.
 *
 * Entry timestamps are all set to the export time rather than "now" per entry, so the same data exported twice
 * produces the same archive — the determinism docs/11 section 5.2 asks for.
 */
class Zip(stream: OutputStream, private val entryTimeMillis: Long) : AutoCloseable {

    private val zip = ZipOutputStream(stream)

    fun text(path: String, content: String) = bytes(path, content.toByteArray(Charsets.UTF_8))

    fun bytes(path: String, content: ByteArray) {
        zip.putNextEntry(entry(path))
        zip.write(content)
        zip.closeEntry()
    }

    /** Streams from a producer so a large file is copied in chunks, not loaded whole. */
    fun stream(path: String, write: (OutputStream) -> Unit) {
        zip.putNextEntry(entry(path))
        write(NonClosing(zip))
        zip.closeEntry()
    }

    private fun entry(path: String) = ZipEntry(path).apply { time = entryTimeMillis }

    fun finish() = zip.finish()

    override fun close() = zip.close()

    /** Hands a writer an [OutputStream] it can `use {}` without ending the whole archive. */
    private class NonClosing(private val inner: OutputStream) : OutputStream() {
        override fun write(b: Int) = inner.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = inner.write(b, off, len)
        override fun flush() = inner.flush()
        override fun close() = Unit
    }
}
