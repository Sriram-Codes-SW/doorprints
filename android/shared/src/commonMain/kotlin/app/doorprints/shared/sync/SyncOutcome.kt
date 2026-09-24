package app.doorprints.shared.sync

import app.doorprints.shared.api.ApiException
import kotlinx.io.IOException

/**
 * Result of the last sync, stored (Android: DataStore) as a small code, not as text, so the UI can show it in the
 * current app language and never displays server response bodies (threat model F-12). [serverReset] is set when this
 * sync found the server behind this phone (S4b-BL-20, [SyncRules.serverBehind]) and sent everything again; the
 * screens then say so before the counts.
 */
data class SyncOutcome(
    val kind: Kind,
    val pushed: Int = 0,
    val pulled: Int = 0,
    val photosWaiting: Int = 0,
    val httpCode: Int = 0,
    val serverReset: Boolean = false,
) {
    enum class Kind { OK, NOT_CONFIGURED, NETWORK, AUTH, CAPTIVE_PORTAL, RATE_LIMITED, SERVER, UNKNOWN }

    /** Five fields as before; a sixth, `R`, only after a server reset, so every other outcome is stored as it was. */
    fun encode(): String =
        (listOf(kind.name, pushed, pulled, photosWaiting, httpCode) + listOfNotNull(RESET.takeIf { serverReset }))
            .joinToString("|")

    companion object {
        fun decode(value: String?): SyncOutcome? {
            val parts = value?.split('|') ?: return null
            if (parts.size != 5 && !(parts.size == 6 && parts[5] == RESET)) return null
            val kind = Kind.entries.firstOrNull { it.name == parts[0] } ?: return null
            return SyncOutcome(
                kind, parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0,
                parts[3].toIntOrNull() ?: 0, parts[4].toIntOrNull() ?: 0,
                serverReset = parts.size == 6,
            )
        }

        private const val RESET = "R"

        /**
         * Classifies a sync failure. kotlinx.io.IOException is java.io.IOException on Android (a typealias), so
         * socket, DNS, TLS and timeout errors from OkHttp all count as [Kind.NETWORK], exactly as before.
         */
        fun fromError(e: Throwable): SyncOutcome = when (e) {
            is ApiException -> when (e.kind) {
                ApiException.Kind.AUTH -> SyncOutcome(Kind.AUTH, httpCode = e.code)
                ApiException.Kind.CAPTIVE_PORTAL -> SyncOutcome(Kind.CAPTIVE_PORTAL, httpCode = e.code)
                ApiException.Kind.RATE_LIMITED -> SyncOutcome(Kind.RATE_LIMITED, httpCode = e.code)
                else -> SyncOutcome(Kind.SERVER, httpCode = e.code)
            }
            is IOException -> SyncOutcome(Kind.NETWORK)
            else -> SyncOutcome(Kind.UNKNOWN)
        }
    }
}
