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

package app.doorprints.drive.auth.browser

import android.content.Context
import app.doorprints.drive.auth.GoogleAuthorizer
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/** Is Google Play services present and usable on this phone? */
fun playServicesAvailable(context: Context): Boolean =
    try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (_: Exception) {
        false
    }

/**
 * Owner decision 2026-10-06: Play services stays the default sign-in; the system browser with PKCE is the fallback for
 * phones without it. [playAvailable] is a seam for tests.
 */
fun chooseGoogleAuthorizer(
    context: Context,
    play: GoogleAuthorizer,
    browser: GoogleAuthorizer,
    playAvailable: (Context) -> Boolean = ::playServicesAvailable,
): GoogleAuthorizer = if (playAvailable(context)) play else browser
