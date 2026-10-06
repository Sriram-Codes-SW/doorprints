package app.doorprints.drive.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** The format version every state file carries as `"v"`; a file of another version reads as absent instead of being misread. */
internal const val STATE_VERSION = 1

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Reads [file] as [D]: null when absent, corrupt (not JSON, truncated, wrong shape), or of another version
 * ([versionOf] is not [STATE_VERSION]). [convert] may reject a decoded value (a bad hex string, an unknown enum) by
 * returning null or throwing; that too is "absent". Never throws.
 */
internal fun <D, T : Any> readState(file: AtomicJsonFile, serializer: KSerializer<D>, versionOf: (D) -> Int, convert: (D) -> T?): T? {
    val text = file.readText() ?: return null
    return try {
        val dto = json.decodeFromString(serializer, text)
        if (versionOf(dto) != STATE_VERSION) null else convert(dto)
    } catch (_: Exception) {
        null
    }
}

internal fun <D> writeState(file: AtomicJsonFile, serializer: KSerializer<D>, dto: D) {
    file.writeText(json.encodeToString(serializer, dto))
}

/**
 * The file name for a Drive folder id: the id itself when it is made of letters, digits, `_` and `-` (what Drive ids
 * are), else `h.` and the hash of it. No id can name a file outside the store's folder.
 */
internal fun folderFileName(prefix: String, rootId: String): String {
    require(rootId.isNotBlank()) { "root id is blank" }
    val safe = SAFE_ID.matches(rootId)
    val part = if (safe) rootId else "h." + MessageDigest.getInstance("SHA-256").digest(rootId.toByteArray(Charsets.UTF_8)).toHexString()
    return "$prefix-$part.json"
}

private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,100}")

internal fun File.atomic() = AtomicJsonFile(this)
