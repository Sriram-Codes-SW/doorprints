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

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import app.doorprints.ui.drive.ClipboardSeam

/**
 * Copies a secret (the recovery key, an enrolment message) marked sensitive, so Android 13 and later neither shows it in
 * the clipboard preview nor keeps it in the history (`ClipDescription.EXTRA_IS_SENSITIVE`); older versions get a plain clip.
 * [sdk] is a seam: the unit tests run on one Android image (35) and pin both branches through it.
 */
class AndroidClipboardSeam(private val context: Context, private val sdk: Int = Build.VERSION.SDK_INT) : ClipboardSeam {
    override fun copySensitive(text: String) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(LABEL, text)
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        manager.setPrimaryClip(clip)
    }

    private companion object {
        const val LABEL = "Doorprints"
    }
}
