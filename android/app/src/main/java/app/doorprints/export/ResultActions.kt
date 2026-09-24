package app.doorprints.export

import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import app.doorprints.R
import app.doorprints.shared.export.ExportFormat
import java.io.File

/**
 * Which result screens are on screen right now (at least STARTED), so a worker that finishes can tell whether
 * anyone saw the result. When nobody did — the user left the app, or went back before a long export finished — the
 * worker posts a notification instead (UX review, round 4). Set by the screens with `LifecycleStartEffect`.
 */
object ScreenWatch {
    @Volatile var exportScreen: Boolean = false
    @Volatile var importScreen: Boolean = false
    @Volatile var settingsScreen: Boolean = false
}

/**
 * The "Open" and "Share" follow-ups after an export (docs/11 section 5.2: "Saved. Open or Share"), used by the
 * Export screen and by the completion notification, so both offer the same thing.
 */
object ResultActions {

    /**
     * A URI another app can read: the `content://` document the user picked, or the cache file behind the app's
     * `FileProvider` (a `file://` path would crash the receiving app with a FileUriExposedException). Null when a
     * cache path is outside what `file_paths.xml` exposes.
     */
    fun readableUri(context: Context, target: String): Uri? =
        if (target.startsWith("content://")) {
            Uri.parse(target)
        } else {
            runCatching { FileProvider.getUriForFile(context, context.packageName + ".files", File(target)) }.getOrNull()
        }

    /** True for a copy written into `cache/exports/` for the share sheet rather than saved where the user chose. */
    fun isShareCopy(target: String): Boolean = !target.startsWith("content://")

    /**
     * The share sheet for [uri]. The URI is set as ClipData as well as EXTRA_STREAM: the read grant travels with
     * ClipData, and `startActivity` only copies EXTRA_STREAM into it for an in-process launch, not for a
     * notification's PendingIntent.
     */
    fun share(context: Context, uri: Uri, format: ExportFormat): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType(format.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        return Intent.createChooser(send, context.getString(R.string.export_share))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Opens [uri] in whatever app handles the format (a browser for HTML, a PDF viewer, a spreadsheet app). */
    fun view(uri: Uri, format: ExportFormat): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, format.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Notification actions for a finished export: Open (saved copies only) and Share. */
    fun notificationActions(context: Context, target: String, format: ExportFormat): List<NotificationCompat.Action> {
        val uri = readableUri(context, target) ?: return emptyList()
        val actions = mutableListOf<NotificationCompat.Action>()
        if (!isShareCopy(target)) {
            // Through a chooser, so a phone with no viewer for the format says so instead of doing nothing.
            val open = Intent.createChooser(view(uri, format), context.getString(R.string.export_open))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            open.clipData = ClipData.newRawUri(null, uri)
            actions += NotificationCompat.Action(0, context.getString(R.string.export_open), activity(context, OPEN_REQUEST, open))
        }
        actions += NotificationCompat.Action(
            0, context.getString(R.string.export_share), activity(context, SHARE_REQUEST, share(context, uri, format)),
        )
        return actions
    }

    private fun activity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private const val OPEN_REQUEST = 110
    private const val SHARE_REQUEST = 111
}
