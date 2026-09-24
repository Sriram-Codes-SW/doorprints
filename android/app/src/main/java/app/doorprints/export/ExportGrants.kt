package app.doorprints.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.doorprints.DoorprintsApp

/**
 * The persisted write grant on a "Save to…" document, and how long the app keeps it.
 *
 * **Why persist it at all.** The grant `ACTION_CREATE_DOCUMENT` hands back belongs to the *activity* that received
 * the result, not to the app: AOSP `ActivityRecord` gives activity-result grants to that record's
 * `UriPermissionOwner`, and `removeFromHistory()` drops them (`removeUriPermissionsLocked`). An export is built to
 * outlive the screen, so without a persisted grant the app loses the document the moment the user backs out of the
 * app or swipes it from Recents. The already-open output stream keeps working, but everything after it does not:
 * Stop could no longer delete the truncated file (the screen would say "Nothing was saved" over a broken
 * `Doorprints-….zip` in Downloads), a retry after a system stop could not reopen it, the provider would not say
 * the file's name, and the "Your copy is saved" notification's Open and Share would silently do nothing.
 * DocumentsUI returns `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` for CREATE_DOCUMENT, so [take] can keep it.
 *
 * **Why release it.** Persisted grants are capped per app (128 before Android 11, 512 since), and every export
 * would otherwise use one up for ever. So:
 *  - a run that failed, or was abandoned and its file deleted, gives its grant back ([release], from
 *    [ExportWorker]);
 *  - a finished export keeps its grant so the notification's Open and Share keep working, but only the newest
 *    [KEPT] do: [hold] records each new grant and releases the ones [retainNewestGrants] pushes out.
 *
 * The weekly backup's folder grant is separate (Settings, `AutoBackupWorker`) and never touched here: only URIs
 * recorded by [hold] are ever released by this object.
 */
object ExportGrants {

    /** How many finished exports keep Open and Share working from their notification. */
    const val KEPT = 5

    private const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /**
     * Persists the grant on a document the user just picked. Call it synchronously in the picker callback, before
     * the export is started, while the activity still holds the transient grant. Read *and* write when the provider
     * allows it — read is what Open and Share need — with write alone as the fallback for a provider that granted
     * only that. False when nothing could be persisted (the export still runs; it just does not survive a back-out).
     */
    fun take(context: Context, uri: Uri): Boolean {
        val resolver = context.contentResolver
        val both = runCatching { resolver.takePersistableUriPermission(uri, READ_WRITE) }
        if (both.isSuccess) return true
        return runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.isSuccess
    }

    /** Records [target] as the newest held grant and releases the ones beyond [KEPT]. */
    suspend fun hold(context: Context, target: String) {
        if (!target.startsWith("content://")) return
        val app = context.applicationContext
        val older = settingsOf(app).holdExportGrant(target, KEPT)
        for (uri in older) releaseNow(app, uri)
    }

    /**
     * Gives back the grant on [target] once nothing will write to it or open it again: a failed run, or an
     * abandoned one whose file has been deleted. A cache target (a "Share" copy) has no grant and is ignored.
     */
    suspend fun release(context: Context, target: String) {
        if (!target.startsWith("content://")) return
        val app = context.applicationContext
        runCatching { settingsOf(app).dropExportGrant(target) }
        releaseNow(app, target)
    }

    /** Best effort; see [Saf.releaseGrant]. */
    private fun releaseNow(context: Context, uri: String) = Saf.releaseGrant(context, uri)

    private fun settingsOf(app: Context) = (app as DoorprintsApp).container.settings
}
