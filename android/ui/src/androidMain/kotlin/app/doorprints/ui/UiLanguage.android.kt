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

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

// The language Android resolved for the app's strings (S4b-BL-18): AppLocale.applyDefault keeps the default locale on
// it, on every API level. The configuration's first locale can be a language the app does not ship (a phone set to
// [Marathi, Hindi] shows Hindi strings), so it is only read to recompose after a configuration change.
@Composable
actual fun uiLanguage(): String {
    LocalConfiguration.current
    return appLanguage()
}
