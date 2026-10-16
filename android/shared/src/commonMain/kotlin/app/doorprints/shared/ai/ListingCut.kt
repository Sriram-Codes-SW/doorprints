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

package app.doorprints.shared.ai

/**
 * The pasted listing text as *Fill in from listing text* sends it (S4b-BL-182): trimmed, and cut to [of]'s `cap` UTF-16
 * units (the unit the server counts), never in the middle of an emoji. [leftOut] is how many units of the trimmed text
 * were not sent. The website's `cutListing` gives the same answers (shared vectors `listingCut`).
 */
data class ListingCut(val text: String, val leftOut: Int) {
    companion object {
        fun of(text: String, cap: Int = OnDeviceAi.MAX_INPUT_CHARS): ListingCut {
            val t = text.trim()
            if (t.length <= cap) return ListingCut(t, 0)
            var end = cap
            if (end > 0 && t[end - 1].isHighSurrogate()) end--
            return ListingCut(t.substring(0, end), t.length - end)
        }
    }
}
