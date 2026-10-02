package app.doorprints.drive

import android.content.Context
import android.net.Uri
import app.doorprints.drive.backup.ImportDownload
import app.doorprints.drive.backup.StagingSink
import app.doorprints.drive.connect.DriveImportHandoff
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Hands a backup from Google Drive to the existing *Import a backup* screen as a file (docs/15 §1.4, §7 phase 3). The
 * decrypted ZIP is written to a private cache file as it is verified; the Import screen then copies it into its own
 * staging like any picked file, and runs its usual checks and preview. Older hand-off files are removed on the next one.
 */
class AndroidDriveImportHandoff(private val context: Context) : DriveImportHandoff {
    @Volatile private var ready: File? = null
    @Volatile private var writing: File? = null
    @Volatile private var stream: FileOutputStream? = null

    override fun staging(): StagingSink {
        val dir = File(context.cacheDir, "drive-import").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        ready = null
        val file = File(dir, "${UUID.randomUUID()}.zip")
        writing = file
        val out = FileOutputStream(file)
        stream = out
        return object : StagingSink {
            override fun write(buffer: ByteArray, offset: Int, length: Int) = out.write(buffer, offset, length)
            override fun discard() {
                runCatching { out.close() }
                file.delete()
                if (writing === file) writing = null
            }
        }
    }

    override suspend fun open(download: ImportDownload.Verified) {
        val file = writing ?: return
        writing = null
        runCatching { stream?.close() }
        stream = null
        ready = file
    }

    /** The file for the Import screen, once; the closed stream is flushed by the time the service returns. */
    fun take(): String? {
        val file = ready ?: return null
        ready = null
        return if (file.isFile && file.length() > 0) Uri.fromFile(file).toString() else null
    }
}
