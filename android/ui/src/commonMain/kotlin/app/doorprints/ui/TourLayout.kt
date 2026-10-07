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

import androidx.compose.ui.geometry.Rect

/** Where the tour's card sits relative to the highlight. */
enum class TourSide { BELOW, ABOVE, CENTRE }

/**
 * The card's side and the most height it may take there. The card scrolls its text inside [maxHeight] and keeps its
 * buttons in view, so it never grows over the highlight.
 */
data class TourPlacement(val side: TourSide, val maxHeight: Float) {
    /** The card's top edge for a card [height] tall (at most [maxHeight]) in [area]: hugging the bottom, the top, or centred. */
    fun top(area: Rect, height: Float, gap: Float): Float = when (side) {
        TourSide.BELOW -> area.bottom - gap - height
        TourSide.ABOVE -> area.top + gap
        TourSide.CENTRE -> area.top + (area.height - height) / 2f
    }
}

/**
 * The tour's geometry, all in the overlay's pixels and free of any UI so it can be tested: which part of a control to
 * highlight ([spot]) and where the card goes so it never covers it ([place]). The web's tour does the same with a
 * highlight box and a card above or below it (`tour-overlay.ts`).
 */
object TourLayout {
    /** A highlight is never taller than this share of the screen: a long section is highlighted by its top part. */
    const val SPOT_FRACTION = 0.35f

    /** A control must be at least this much on screen to be highlighted; mostly scrolled away, the step shows plain. */
    const val VISIBLE_FRACTION = 0.5f

    /**
     * The highlight for a control at [target] on [screen]: the control with [pad] around it, cut to the screen, and no
     * taller than [SPOT_FRACTION] of it, nor so tall that less than [minCard] (plus [gap] on each side) is left on the
     * roomier side for the card. Null when there is no control, or less than half of it is on the screen.
     */
    fun spot(target: Rect?, screen: Rect, pad: Float, gap: Float, minCard: Float): Rect? {
        if (target == null || target.width <= 0f || target.height <= 0f) return null
        val padded = Rect(target.left - pad, target.top - pad, target.right + pad, target.bottom + pad)
        val seen = padded.intersect(screen)
        if (seen.width <= 0f || seen.height <= 0f) return null
        if (seen.height < padded.height * VISIBLE_FRACTION) return null
        val tallest = minOf(screen.height * SPOT_FRACTION, screen.height - 2f * (minCard + gap))
        if (tallest <= 0f) return null
        return if (seen.height <= tallest) seen else Rect(seen.left, seen.top, seen.right, seen.top + tallest)
    }

    /**
     * Where a card [need] tall goes in [area] (the screen without the bars): below [spot] when it fits there, else above
     * it when that has more room, else on the side with more room; [clear] is left between the card and the highlight and
     * [gap] to the area's edge. No [spot]: in the middle, or at the bottom when [bottom] (the first-run offer).
     */
    fun place(area: Rect, spot: Rect?, need: Float, gap: Float, clear: Float, bottom: Boolean = false): TourPlacement {
        if (spot == null) {
            return TourPlacement(if (bottom) TourSide.BELOW else TourSide.CENTRE, maxOf(0f, area.height - 2f * gap))
        }
        val below = maxOf(0f, area.bottom - gap - maxOf(spot.bottom, area.top) - clear)
        val above = maxOf(0f, minOf(spot.top, area.bottom) - area.top - gap - clear)
        val useAbove = need > below && above > below
        return TourPlacement(if (useAbove) TourSide.ABOVE else TourSide.BELOW, if (useAbove) above else below)
    }
}
