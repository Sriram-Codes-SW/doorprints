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

/**
 * The system's location prompt for this screen (CMP-5): returns a function that shows it, asking for precise and
 * approximate location together, as Android recommends; [onResult] runs when the user has answered, whatever the
 * answer (read the new state from [LocationAsk.refresh] and [PlatformServices.locationAccess]). The caller records
 * the ask first ([LocationAsk.markAsked]). Android: an activity-result launcher for `LOCATION_PERMISSIONS`, registered
 * with the composition, so the answer arrives even after a rotation. iOS (CMP-8b): `CLLocationManager`'s when-in-use
 * prompt; [onResult] runs when the authorization changes.
 */
@Composable
expect fun rememberLocationPermissionRequest(onResult: () -> Unit): () -> Unit

/**
 * The system's notification prompt (CMP-5, [rememberNotificationAsk]): returns a function that shows it where the
 * platform has one and [onResult] once it is answered; where there is none (Android below API 33, or no activity to
 * handle the request) [onResult] runs at once. iOS: no notifications yet (CMP-8b), so [onResult] runs at once.
 */
@Composable
expect fun rememberNotificationPermissionRequest(onResult: () -> Unit): () -> Unit
