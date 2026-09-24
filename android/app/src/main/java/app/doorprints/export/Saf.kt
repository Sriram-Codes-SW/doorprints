package app.doorprints.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import app.doorprints.R
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * The Storage Access Framework side of exporting (docs/11 section 5.2: "Never app-specific storage, so the file
 * survives uninstall").
 *
 * `androidx.documentfile` is deliberately not used: `DocumentsContract` does everything needed here (create a
 * document in a granted tree, list it, delete one) in about sixty lines, and the app takes no new dependency.
 */
object Saf {

    /** Opens the destination for writing: a `content://` document the user picked, or a cache file to share. */
    fun openOutput(context: Context, target: String): OutputStream {
        if (!target.startsWith("content://")) {
            return File(target).apply { parentFile?.mkdirs() }.outputStream()
        }
        val uri = Uri.parse(target)
        // "wt" truncates, so overwriting a longer old file does not leave its tail behind. Not every document
        // provider implements the "t" flag, so plain "w" is the fallback (the file the user picked is normally
        // brand new anyway, and then there is nothing to truncate).
        val resolver = context.contentResolver
        val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
        // A FileNotFoundException, like the resolver's own "cannot open" failures, so [ExportProblem.of] reads it
        // as CANNOT_WRITE. The message is for logcat only; the screen never shows it.
        return stream ?: throw FileNotFoundException("No output stream for the chosen document")
    }

    /** The document URI of a granted tree's root, which is what child operations hang off. */
    fun treeRoot(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    /** Creates a file in a granted tree and returns its URI, or null when the grant is gone. */
    fun createInTree(context: Context, treeUri: Uri, mimeType: String, name: String): Uri? = runCatching {
        DocumentsContract.createDocument(context.contentResolver, treeRoot(treeUri), mimeType, name)
    }.getOrNull()

    /** [lastModifiedMillis] is 0 when the provider does not report one; see [oldestBeyondRetention]. */
    data class Child(val uri: Uri, val name: String, val lastModifiedMillis: Long)

    /** Lists a granted tree's direct children. Empty when the grant has been revoked. */
    fun listTree(context: Context, treeUri: Uri): List<Child> = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val out = mutableListOf<Child>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                // COLUMN_LAST_MODIFIED is optional in the DocumentsProvider contract; 0 means "not reported".
                val modified = if (cursor.isNull(2)) 0L else cursor.getLong(2)
                out += Child(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name, modified)
            }
        }
        out
    }.getOrDefault(emptyList())

    /**
     * The name the user sees for a document ("Doorprints-2026-09-22.html"), from `OpenableColumns.DISPLAY_NAME`.
     * Null when the provider does not say or the query fails; never the document id, which is what the last path
     * segment of a `content://` URI is ("msf%3A1000001234") and means nothing to a person.
     */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    /**
     * A readable name for a granted folder, for Settings' "Saved to: …" line.
     *
     * On the device's own storage (`com.android.externalstorage.documents`) the tree's document id is
     * `<volume>:<path>`, so the part after the colon is the path the user browsed to ("Download/Doorprints") —
     * more useful than the bare folder name when a backup has to be found later. Other providers (Drive, an SD
     * card app) use opaque ids, so they get the root document's display name instead. Blocking: call off the
     * main thread.
     */
    fun folderLabel(context: Context, treeUri: Uri): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val path = if (treeUri.authority == EXTERNAL_STORAGE_AUTHORITY) docId.substringAfter(':', "") else ""
        path.trim('/').takeIf { it.isNotEmpty() } ?: displayName(context, treeRoot(treeUri))
    }.getOrNull()

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

    /**
     * Where a "Save to…" document went, for "Saved to Download: Doorprints-2026-09-22.html" (UX review, round 11):
     * the folder it is in on the device's own storage (`primary:Download/Doorprints-….html` → "Download"), or
     * "Downloads" for the Downloads provider. Null for anything else: other providers use opaque document ids, and
     * their roots (Drive's account, an SD card app) can only be listed with `MANAGE_DOCUMENTS`, which no ordinary
     * app holds. Only string work on the URI, no provider call, so it is safe on any thread.
     */
    fun locationName(context: Context, uri: Uri): String? = runCatching {
        val docId = DocumentsContract.getDocumentId(uri)
        when (uri.authority) {
            EXTERNAL_STORAGE_AUTHORITY -> parentFolder(docId.substringAfter(':', ""))
            // "raw:/storage/emulated/0/Download/x.html" on some versions; otherwise an opaque "msf:123" in Downloads.
            DOWNLOADS_AUTHORITY -> if (docId.startsWith("raw:")) {
                parentFolder(docId.removePrefix("raw:"))
            } else {
                context.getString(R.string.export_location_downloads)
            }
            else -> null
        }
    }.getOrNull()

    /** The name of the folder a path's last segment is in, or null at the top of a volume. */
    private fun parentFolder(path: String): String? =
        path.trim('/').substringBeforeLast('/', "").substringAfterLast('/').takeIf { it.isNotBlank() }

    /**
     * Gives back a persisted read/write grant. Best effort: the platform throws when the app holds no grant on
     * [uri] (the user revoked it, or it was never persisted), and that is the state wanted anyway. Releasing read
     * and write together is safe when only one of them was persisted; the flags just clear.
     */
    fun releaseGrant(context: Context, uri: String) {
        if (uri.isBlank()) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun delete(context: Context, uri: Uri): Boolean = runCatching {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    }.getOrDefault(false)

    /**
     * Whether the provider says it can rename this document (`FLAG_SUPPORTS_RENAME`). False when it does not say,
     * or the query fails: a caller that plans to rename must know *before* it writes, not find out afterwards.
     */
    fun supportsRename(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null, null, null,
        )?.use { cursor ->
            cursor.moveToFirst() && !cursor.isNull(0) &&
                (cursor.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
        } ?: false
    }.getOrDefault(false)

    /** Renames a document; the result is its (possibly new) URI, or null when the provider refused. */
    fun rename(context: Context, uri: Uri, name: String): Uri? = runCatching {
        DocumentsContract.renameDocument(context.contentResolver, uri, name)
    }.getOrNull()

    /** Keeps the newest [keep] files whose display name passes [isOurs], deleting the rest. */
    fun trim(context: Context, treeUri: Uri, keep: Int, isOurs: (String) -> Boolean) {
        val ours = listTree(context, treeUri).filter { isOurs(it.name) }
        for (old in oldestBeyondRetention(ours, keep, { it.lastModifiedMillis }, { it.name })) {
            delete(context, old.uri)
        }
    }
}

/**
 * Everything past the newest [keep] items: what retention should delete.
 *
 * Ordering is by the provider's last-modified time, **not** by display name. Sorting by name looks right while
 * every file is `Doorprints-backup-<date>.zip`, but a provider that de-duplicates a name produces
 * `Doorprints-backup-2026-09-22 (1).zip`, and `' '` (0x20) sorts before `'.'` (0x2E) — so the newer file sorts
 * as the older one and retention deletes today's backup instead of the oldest. The name is only the tie-break,
 * for a provider that reports no timestamp at all (every item then has 0 and the old behaviour returns).
 *
 * Generic and free of Android types so a JVM unit test can pin it; see `SafTrimTest`.
 */
fun <T> oldestBeyondRetention(
    items: List<T>,
    keep: Int,
    modified: (T) -> Long,
    name: (T) -> String,
): List<T> {
    if (keep < 0) return emptyList()
    if (items.size <= keep) return emptyList()
    val newestLast = items.sortedWith(compareBy<T> { modified(it) }.thenBy { name(it) })
    return newestLast.dropLast(keep)
}

/**
 * "Save as…" with a media type and a suggested name chosen at the moment of the tap.
 *
 * `ActivityResultContracts.CreateDocument` fixes its media type when the contract is built, which does not work
 * here: the user picks the format on the screen, long after the launcher was remembered.
 */
class CreateExportDocument : ActivityResultContract<CreateExportDocument.Request, Uri?>() {

    data class Request(val mimeType: String, val fileName: String)

    override fun createIntent(context: Context, input: Request): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/**
 * "Open a backup…". Kept next to [CreateExportDocument] so both SAF contracts read the same way.
 *
 * The input is where the picker should open (UX review, 2026-09-22): the weekly backup folder when one is set, as
 * a document URI inside the granted tree ([Saf.treeRoot]), so someone restoring one of those backups months later
 * does not have to hunt for the folder they once picked. `EXTRA_INITIAL_URI` is API 26+, which is the app's
 * minSdk; a provider that does not know the location simply ignores it. Null opens at the system default.
 */
class OpenBackupDocument : ActivityResultContract<Uri?, Uri?>() {

    override fun createIntent(context: Context, input: Uri?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            // Providers disagree on a ZIP's type: octet-stream is common, and files that came from Windows or
            // some cloud apps are labelled x-zip-compressed or x-zip. Leaving one out greys the backup out in
            // the picker, with no hint why. `application/json` is the bare data.json that the server's
            // `GET /api/export` downloads (docs/schemas/README.md section 2), which BackupReader also reads.
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/zip", "application/octet-stream", "application/x-zip-compressed", "application/x-zip",
                    "application/json",
                ),
            )
            .apply { if (input != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, input) }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
