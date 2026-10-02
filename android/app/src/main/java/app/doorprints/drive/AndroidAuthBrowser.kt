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

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.doorprints.drive.connect.AuthBrowser
import app.doorprints.drive.connect.BrowserResult
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opens Google's consent page in the system browser (`ACTION_VIEW`, no library: docs/15 §5.5) and waits for the
 * redirect that `MainActivity` receives. Only an `https://accounts.google.com/` address is ever opened.
 */
class AndroidAuthBrowser(private val context: Context) : AuthBrowser {
    override suspend fun authorize(url: String, redirectUri: String): BrowserResult {
        if (!url.startsWith("https://accounts.google.com/")) return BrowserResult.Unavailable
        val waiting = DriveRedirects.begin()
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            val activity = ForegroundActivity.current
            try {
                if (activity != null) activity.startActivity(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                return BrowserResult.Unavailable
            }
            val answer = withTimeoutOrNull(DriveRedirects.WAIT_MS) { waiting.await() }
            return if (answer == null) BrowserResult.Cancelled else BrowserResult.Redirected(answer)
        } finally {
            DriveRedirects.end(waiting)
        }
    }
}
