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

package app.doorprints.shared.export

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.posix.ENOSPC
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.errno
import platform.posix.fstat
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.stat
import platform.zlib.MAX_WBITS
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

/*
 * The iPhone's side of the common ZIP code (S4b-BL-81): SHA-256 from CommonCrypto, raw DEFLATE from the system zlib
 * (`-lz`, ios/project.yml) and files through POSIX, which gives the random access and the header patches the ZIP needs.
 */

/** [ArchiveTools] on iOS. */
@OptIn(ExperimentalForeignApi::class)
val iosArchiveTools: ArchiveTools = ArchiveTools(sha256 = ::CommonCryptoSha256, inflate = ::zlibInflateRaw)

/** CommonCrypto's SHA-256; its context lives on the native heap until [digest]. */
@OptIn(ExperimentalForeignApi::class)
private class CommonCryptoSha256 : Sha256 {
    private val context = nativeHeap.alloc<CC_SHA256_CTX>().also { CC_SHA256_Init(it.ptr) }
    private var done = false

    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        check(!done)
        if (length == 0) return
        bytes.usePinned { CC_SHA256_Update(context.ptr, it.addressOf(offset), length.convert()) }
    }

    /** Finishes the hash and frees its native context; the object cannot be used again. */
    override fun digest(): ByteArray {
        check(!done)
        done = true
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        out.usePinned { CC_SHA256_Final(it.addressOf(0).reinterpret<UByteVar>(), context.ptr) }
        nativeHeap.free(context)
        return out
    }
}

/**
 * Raw DEFLATE (window bits -15) through zlib, in 64 KiB steps; null once more than [maxBytes] came out, which is
 * checked after every step, so a bomb costs [maxBytes] and a step. Throws [IllegalArgumentException] for broken data.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun zlibInflateRaw(compressed: ByteArray, maxBytes: Int): ByteArray? = memScoped {
    val stream = alloc<z_stream>()
    if (inflateInit2_(stream.ptr, -MAX_WBITS, ZLIB_VERSION, sizeOf<z_stream>().convert()) != Z_OK) return@memScoped null
    val out = ByteArrayBuilder()
    val chunk = ByteArray(64 * 1024)
    // An empty input still needs a valid pointer.
    val input = if (compressed.isEmpty()) ByteArray(1) else compressed
    var result: ByteArray? = null
    try {
        input.usePinned { pinnedIn ->
            stream.next_in = pinnedIn.addressOf(0).reinterpret()
            stream.avail_in = compressed.size.convert()
            chunk.usePinned { pinnedOut ->
                var going = true
                while (going) {
                    stream.next_out = pinnedOut.addressOf(0).reinterpret()
                    stream.avail_out = chunk.size.convert()
                    val status = inflate(stream.ptr, Z_NO_FLUSH)
                    val produced = chunk.size - stream.avail_out.toInt()
                    going = false
                    if (out.size + produced <= maxBytes) {
                        out.append(chunk, produced)
                        when (status) {
                            Z_STREAM_END -> result = out.toByteArray()
                            Z_OK -> {
                                // No progress with all input used: the stream was cut short.
                                require(produced > 0 || stream.avail_in.toInt() > 0) { "DEFLATE cut short" }
                                going = true
                            }
                            else -> throw IllegalArgumentException("broken DEFLATE ($status)")
                        }
                    }
                }
            }
        }
    } finally {
        inflateEnd(stream.ptr)
    }
    result
}

/** A growing byte array without a JVM `ByteArrayOutputStream`. */
private class ByteArrayBuilder {
    private var bytes = ByteArray(64 * 1024)
    var size = 0
        private set

    fun append(from: ByteArray, count: Int) {
        if (size + count > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + count))
        from.copyInto(bytes, size, 0, count)
        size += count
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)
}

/** A POSIX call that failed, with its `errno`; [noSpace] when the disk is full (ENOSPC). */
class PosixFileException(val errno: Int, what: String) : Exception("$what failed (errno $errno)") {
    val noSpace: Boolean get() = errno == ENOSPC
}

/**
 * A new file at [path] (replacing one there), private to the app (0600), written through a 64 KiB buffer; [close] it.
 * The copies' [CopySink].
 */
@OptIn(ExperimentalForeignApi::class)
class PosixFileSink(path: String) : CopySink, AutoCloseable {
    private val fd = platform.posix.open(path, O_WRONLY or O_CREAT or O_TRUNC, 0x180)
    private val buffer = ByteArray(64 * 1024)
    private var buffered = 0
    private var written = 0L

    init {
        if (fd < 0) throw PosixFileException(errno, "open")
    }

    override val position: Long get() = written + buffered

    /**
     * Appends to the buffer, which is written out when full; a chunk as large as the buffer goes straight to the
     * file.
     */
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length >= buffer.size) {
            flush()
            writeAt(written, bytes, offset, length)
            written += length
            return
        }
        if (buffered + length > buffer.size) flush()
        bytes.copyInto(buffer, buffered, offset, offset + length)
        buffered += length
    }

    /** Overwrites bytes already written (the ZIP header fields known only at the end); the buffer is flushed first. */
    override fun patch(at: Long, bytes: ByteArray) {
        require(at + bytes.size <= position)
        flush()
        writeAt(at, bytes, 0, bytes.size)
    }

    /** Writes the buffered bytes to the file. */
    private fun flush() {
        if (buffered == 0) return
        writeAt(written, buffer, 0, buffered)
        written += buffered
        buffered = 0
    }

    /**
     * Writes all of the range at [at] with `pwrite`, looping over short writes; a failure is a
     * [PosixFileException].
     */
    private fun writeAt(at: Long, bytes: ByteArray, offset: Int, length: Int) {
        var done = 0
        bytes.usePinned { pinned ->
            while (done < length) {
                val n = pwrite(fd, pinned.addressOf(offset + done), (length - done).convert(), at + done)
                if (n <= 0) throw PosixFileException(errno, "write")
                done += n.toInt()
            }
        }
    }

    override fun close() {
        try {
            flush()
        } finally {
            platform.posix.close(fd)
        }
    }
}

/** A file read at any position (the staged backup); [close] it. */
@OptIn(ExperimentalForeignApi::class)
class PosixFileSource(path: String) : RandomSource, AutoCloseable {
    private val fd = platform.posix.open(path, O_RDONLY)

    override val size: Long

    init {
        if (fd < 0) throw PosixFileException(errno, "open")
        size = memScoped {
            val info = alloc<stat>()
            if (fstat(fd, info.ptr) != 0) {
                platform.posix.close(fd)
                throw PosixFileException(errno, "stat")
            }
            info.st_size
        }
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = buffer.usePinned { pread(fd, it.addressOf(offset), length.convert(), position) }
        if (n < 0) throw PosixFileException(errno, "read")
        return n.toInt()
    }

    override fun close() {
        platform.posix.close(fd)
    }
}
