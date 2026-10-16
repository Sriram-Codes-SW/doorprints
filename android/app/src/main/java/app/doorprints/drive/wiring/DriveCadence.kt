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

package app.doorprints.drive.wiring

import android.content.Context

/** The Android gate: the level and the clock in a small private preferences file. */
fun DriveCadenceGate.Companion.of(context: Context) = DriveCadenceGate(PrefsCadenceStore(context.applicationContext))

/** [CadenceStore] over a small private preferences file. */
class PrefsCadenceStore(context: Context) : CadenceStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    override fun load() = CadenceState(prefs.getInt(LEVEL, 0), prefs.getLong(LAST, 0L))
    override fun save(state: CadenceState) {
        prefs.edit().putInt(LEVEL, state.level).putLong(LAST, state.lastPassAt).apply()
    }

    private companion object {
        const val FILE = "drive-cadence"
        const val LEVEL = "level"
        const val LAST = "lastPassAt"
    }
}
