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

package app.doorprints.shared.sync

import app.doorprints.drive.DriveException
import app.doorprints.shared.api.ApiException
import kotlinx.io.IOException

/**
 * Result of the last sync, stored (Android: DataStore) as a small code, not as text, so the UI can show it in the
 * current app language and never displays server response bodies (threat model F-12). [remoteReset] is set when this
 * sync found the remote behind this phone (S4b-BL-20: the server's [SyncRules.serverBehind], the backend's
 * `SyncBackend.isBehind`; named for any backend since S4b-BL-130) and sent everything again; the screens then say so
 * before the counts.
 */
data class SyncOutcome(
    val kind: Kind,
    val pushed: Int = 0,
    val pulled: Int = 0,
    val photosWaiting: Int = 0,
    val httpCode: Int = 0,
    val remoteReset: Boolean = false,
) {
    /** The groups of result the screens word; the stored form uses the name. */
    enum class Kind { OK, NOT_CONFIGURED, NETWORK, AUTH, CAPTIVE_PORTAL, RATE_LIMITED, SERVER, UNKNOWN }

    /** Five fields as before; a sixth, `R`, only after a server reset, so every other outcome is stored as it was. */
    fun encode(): String =
        (listOf(kind.name, pushed, pulled, photosWaiting, httpCode) + listOfNotNull(RESET.takeIf { remoteReset }))
            .joinToString("|")

    companion object {
        /**
         * Reads the stored form written by [encode]; null for anything else, so a damaged value reads as no result.
         */
        fun decode(value: String?): SyncOutcome? {
            val parts = value?.split('|') ?: return null
            if (parts.size != 5 && !(parts.size == 6 && parts[5] == RESET)) return null
            val kind = Kind.entries.firstOrNull { it.name == parts[0] } ?: return null
            return SyncOutcome(
                kind, parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0,
                parts[3].toIntOrNull() ?: 0, parts[4].toIntOrNull() ?: 0,
                remoteReset = parts.size == 6,
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
            // Google Drive (S4b-BL-115): mapped to the same kinds as the server; a full Drive is SERVER until S4b-BL-118 words it.
            is DriveException -> when (e.kind) {
                DriveException.Kind.UNAUTHORIZED, DriveException.Kind.FORBIDDEN -> SyncOutcome(Kind.AUTH, httpCode = e.httpStatus)
                DriveException.Kind.RATE_LIMITED -> SyncOutcome(Kind.RATE_LIMITED, httpCode = e.httpStatus)
                DriveException.Kind.OFFLINE -> SyncOutcome(Kind.NETWORK)
                else -> SyncOutcome(Kind.SERVER, httpCode = e.httpStatus)
            }
            is IOException -> SyncOutcome(Kind.NETWORK)
            else -> SyncOutcome(Kind.UNKNOWN)
        }
    }
}
