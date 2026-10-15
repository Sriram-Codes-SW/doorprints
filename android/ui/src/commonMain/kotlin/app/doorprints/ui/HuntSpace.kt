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
 * Hunt mode is the house space's (kinds PR C, S4b-BL-274), so its walk ends when the person leaves that space (S4b-BL-304,
 * owner, 2026-10-15): there is no card in another space to stop it, and its *stay here?* alert would make a candidate the
 * house space owns. One rule for Android and the iPhone (the common root calls it with the platform's `stopHunt`).
 */
object HuntSpace {
    /**
     * True when the walk must stop now: the space on screen was the house's ([wasHere]), is not any more ([isHere]), and Hunt
     * mode is running ([active]). A walk that starts while another space is on screen (a reminder's *Start Hunt mode*, a
     * start from the notification) is left alone: only the move out of the house space stops one.
     */
    fun stopsOnLeaving(wasHere: Boolean, isHere: Boolean, active: Boolean): Boolean = wasHere && !isHere && active
}
