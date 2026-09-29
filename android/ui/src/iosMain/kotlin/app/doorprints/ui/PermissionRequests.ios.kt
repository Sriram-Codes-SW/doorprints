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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

// The iOS prompts (CMP-8b). Location: Core Location's "Allow Doorprints to use your location?" ([LocationPrompt]).
// Notifications: none on iOS yet (Hunt mode, their only sender, is hidden by PlatformFeatures.Ios), so the action
// goes ahead as after a refusal.

@Composable
actual fun rememberLocationPermissionRequest(onResult: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onResult)
    val prompt = remember { LocationPrompt { latest() } }
    // A screen left before the answer drops its manager; the answer then reaches the next screen's refresh on resume.
    DisposableEffect(prompt) { onDispose { prompt.release() } }
    return remember(prompt) { { prompt.show() } }
}

@Composable
actual fun rememberNotificationPermissionRequest(onResult: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onResult)
    return remember { { latest() } }
}
