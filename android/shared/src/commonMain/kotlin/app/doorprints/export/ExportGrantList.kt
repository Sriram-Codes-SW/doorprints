package app.doorprints.export

// The list of held "Save to…" grants that SettingsStore keeps (why grants are held and released: :app's
// ExportGrants). In :shared commonMain since CMP-4 P4b, with SettingsStore; the package is :app's, so the callers did
// not change.

/** What [retainNewestGrants] keeps (newest first) and what it gives back. */
data class GrantRetention(val kept: List<String>, val released: List<String>)

/**
 * The bounded, most-recently-used list of held export grants: [added] becomes the newest, a URI already in [held]
 * moves to the front instead of appearing twice, and everything past the newest [keep] is released. [keep] is at
 * least 1, so the grant just taken is never the one given back. Blank entries (a damaged stored value) are dropped.
 *
 * Pure and free of Android types, so `ExportGrantsTest` pins it on the JVM.
 */
fun retainNewestGrants(held: List<String>, added: String, keep: Int): GrantRetention {
    val newestFirst = (listOf(added) + held).filter { it.isNotBlank() }.distinct()
    val limit = keep.coerceAtLeast(1)
    return GrantRetention(kept = newestFirst.take(limit), released = newestFirst.drop(limit))
}

/** The stored form of the held-grant list: one URI per line (a `content://` URI never contains a raw newline). */
fun encodeGrants(grants: List<String>): String = grants.filter { it.isNotBlank() }.joinToString("\n")

/** Reads [encodeGrants]' form back; null or empty is an empty list. */
fun decodeGrants(stored: String?): List<String> =
    stored.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
