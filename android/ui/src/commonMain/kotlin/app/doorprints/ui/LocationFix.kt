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

/**
 * The next step a location note offers ([LocationPermissionNote]): the same on the Map, the house form and the
 * Assistant.
 *  - [ALLOW]: no location, Android will ask: *Allow location* launches the request.
 *  - [OPEN_SETTINGS]: no location, Android will not ask again: *Open settings*.
 *  - [TURN_ON_PRECISE]: approximate only, Android will ask: *Turn on precise location* launches the request again,
 *    and Android shows its "Change to precise location?" prompt.
 *  - [OPEN_SETTINGS_PRECISE]: approximate only, Android will not ask again: *Open settings*, and the note says to
 *    turn on *Use precise location* there (the page itself says location is *Allowed*).
 */
enum class LocationFix { ALLOW, OPEN_SETTINGS, TURN_ON_PRECISE, OPEN_SETTINGS_PRECISE }
