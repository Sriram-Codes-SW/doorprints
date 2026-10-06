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

package app.doorprints.drive

/**
 * The one thing the shared fake Drive (`InMemoryFakeDrive`, compiled into this module's tests from `:shared`'s commonTest)
 * needs that `:shared` keeps `internal`: the 401 rule both Drive clients use (`DriveRetry.kt`): a rejected token is
 * reported and the request made once more with the next one; a second 401 is thrown. Test support only; the production
 * function is the one the real client runs.
 */
internal suspend fun <T> authorized(tokens: TokenProvider, block: suspend (token: String) -> T): T {
    val first = tokens.accessToken()
    return try {
        block(first)
    } catch (e: DriveException) {
        if (e.kind != DriveException.Kind.UNAUTHORIZED) throw e
        tokens.onRejected(first)
        block(tokens.accessToken())
    }
}
