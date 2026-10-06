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

package app.doorprints.testing

import kotlinx.coroutines.test.runTest

/**
 * Runs [block] to its result on the host and on the iPhone simulator (both run `runTest` to completion before it
 * returns): the common-test spelling of the JVM tests' `runBlocking { ... }`, for tests that need the value.
 */
fun <T> blocking(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    runTest { outcome = runCatching { block() } }
    return outcome!!.getOrThrow()
}
