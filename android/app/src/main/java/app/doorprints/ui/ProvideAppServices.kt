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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import app.doorprints.DoorprintsApp

/**
 * Provides the common UI's seams for [content]: [LocalPlatformServices] (`ProvidePlatformServices`) and
 * [LocalAppServices] (the process's [app.doorprints.AndroidAppServices]). MainActivity's content, and each screenshot
 * test's. Every screen is common since CMP-7, so `:app` draws none of its own (the Map's `RootScreens` slot is gone).
 */
@Composable
fun ProvideAppServices(content: @Composable () -> Unit) {
    val services = (LocalContext.current.applicationContext as DoorprintsApp).container.services
    ProvidePlatformServices {
        CompositionLocalProvider(LocalAppServices provides services, content = content)
    }
}
