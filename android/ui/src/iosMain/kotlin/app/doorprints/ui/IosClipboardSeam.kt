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

package app.doorprints.ui

import app.doorprints.ui.drive.ClipboardSeam
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPasteboardOptionExpirationDate
import platform.UIKit.UIPasteboardOptionLocalOnly

/**
 * Copying a secret (the recovery key, an enrolment message) on the iPhone: one plain-text item on the general pasteboard,
 * **local only** (never offered to the person's other devices through Universal Clipboard) and **expiring** after
 * [EXPIRES_SECONDS], so the secret does not sit there for good (docs/15 §9.4). The system's paste banner still shows that
 * something was pasted; nothing else is done with the text, and it is never logged.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosClipboardSeam : ClipboardSeam {
    override fun copySensitive(text: String) {
        UIPasteboard.generalPasteboard.setItems(
            listOf(mapOf("public.utf8-plain-text" to text)),
            options = mapOf(
                UIPasteboardOptionLocalOnly to true,
                UIPasteboardOptionExpirationDate to NSDate.dateWithTimeIntervalSinceNow(EXPIRES_SECONDS),
            ),
        )
    }

    companion object {
        /** Long enough to switch to another app and paste; short enough that a forgotten copy is gone. */
        const val EXPIRES_SECONDS = 120.0
    }
}
