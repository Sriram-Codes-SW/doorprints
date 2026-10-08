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

package app.doorprints

import android.net.Uri
import app.doorprints.ui.DeepLink

/**
 * Which address another app may hand Doorprints as a backup or update file (docs/11 5.28, S4b-BL-169): a `content://`
 * document only. A `file://` path cannot work from another app (scoped storage; since API 24 the sender throws a
 * `FileUriExposedException`), so the manifest does not declare it and this refuses it. The app's own Drive import opens
 * its staged `file:` copy by [DeepLink.ImportFile] directly, not through an intent, so it does not come here.
 */
internal object ImportLink {
    fun of(uri: Uri?): DeepLink.ImportFile? = uri?.takeIf { it.scheme == "content" }?.let { DeepLink.ImportFile(it.toString()) }
}
