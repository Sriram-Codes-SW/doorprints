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

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contrast of every colour pair the screens draw, in both schemes (Wave D; WCAG 1.4.3 and 1.4.11): 4.5:1 for text
 * (the status words and the TAKEN and NOT_CHOSEN ones included, on every surface a card or a chip sits on, and the
 * star glyphs, which read like letters: S4b-BL-110, they were 4.44:1 on white), 3:1 for icons and borders that
 * identify a control, and the map markers on the light tiles. The ratio is WCAG's, from
 * the relative luminance of the sRGB values.
 */
class ContrastTest {
    private fun channel(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private class Check(val what: String, val fg: Color, val bg: Color, val min: Double)

    /** The surfaces text sits on: the page, cards, chips and the lighter containers (sheets, menus, the nav bar). */
    private fun surfaces(s: ColorScheme) = listOf(
        "background" to s.background, "surface" to s.surface, "surfaceVariant" to s.surfaceVariant,
        "surfaceContainerLow" to s.surfaceContainerLow, "surfaceContainer" to s.surfaceContainer,
    )

    private fun pairs(s: ColorScheme, x: DoorprintsColors): List<Check> {
        val text = 4.5
        val ui = 3.0
        val sw = BrandSwitchPalette(s)
        val list = mutableListOf(
            Check("onPrimary on primary", s.onPrimary, s.primary, text),
            Check("onPrimaryContainer on primaryContainer", s.onPrimaryContainer, s.primaryContainer, text),
            Check("primary on primaryContainer", s.primary, s.primaryContainer, text),
            Check("onSecondaryContainer on secondaryContainer", s.onSecondaryContainer, s.secondaryContainer, text),
            Check("onErrorContainer on errorContainer", s.onErrorContainer, s.errorContainer, text),
            Check("onError on error", s.onError, s.error, text),
            Check("onTertiary on tertiary", s.onTertiary, s.tertiary, text),
            Check("onSurfaceVariant on primaryContainer", s.onSurfaceVariant, s.primaryContainer, text),
            Check("onSuccess on success", x.onSuccess, x.success, text),
            Check("star on primaryContainer", x.star, s.primaryContainer, text),
            Check("secondary (--star) on surface", s.secondary, s.surface, text),
            Check("onWarn on warn", x.onWarn, x.warn, text),
        )
        val onSurfaces = listOf(
            "onSurface" to s.onSurface, "onBackground" to s.onBackground, "onSurfaceVariant" to s.onSurfaceVariant,
            "primary" to s.primary, "error" to s.error, "tertiary" to s.tertiary,
            "status new" to x.new, "status shortlisted" to x.shortlisted, "status rejected" to x.rejected,
            "status taken" to x.taken, "status not chosen" to x.notChosen,
        )
        for ((bgName, bg) in surfaces(s)) {
            onSurfaces.forEach { (fgName, fg) -> list += Check("$fgName on $bgName", fg, bg, text) }
            // The stars (always with their words or a description) at the text minimum (S4b-BL-110).
            list += Check("star on $bgName", x.star, bg, text)
            // Graphics: the outline of fields, chips and the unchecked switch's track.
            list += Check("outline on $bgName", s.outline, bg, ui)
            // The switch against the page: the checked track, the unchecked track's border.
            list += Check("switch checked track on $bgName", sw.checkedTrack, bg, ui)
            list += Check("switch unchecked border on $bgName", sw.uncheckedBorder, bg, ui)
        }
        // The thumb against its track, which is what shows the state (Wave E: the unchecked one was 2.93:1 in light).
        list += Check("switch checked thumb on track", sw.checkedThumb, sw.checkedTrack, ui)
        list += Check("switch unchecked thumb on track", sw.uncheckedThumb, sw.uncheckedTrack, ui)
        return list
    }

    private fun failures(scheme: ColorScheme, extra: DoorprintsColors) = pairs(scheme, extra)
        .map { it to ratio(it.fg, it.bg) }
        .filter { (p, r) -> r < p.min }
        .map { (p, r) -> "${p.what}: ${(r * 100).toInt() / 100.0}:1, needs ${p.min}:1" }

    @Test
    fun everyLightPairMeetsWcag() {
        val failed = failures(LightScheme, LightExtra)
        assertTrue(failed.isEmpty(), "Light scheme:\n" + failed.joinToString("\n"))
    }

    @Test
    fun everyDarkPairMeetsWcag() {
        val failed = failures(DarkScheme, DarkExtra)
        assertTrue(failed.isEmpty(), "Dark scheme:\n" + failed.joinToString("\n"))
    }

    /** The markers sit on the light map tiles in both themes; OpenFreeMap's land is about #F2EFE9. */
    @Test
    fun markersStandOutOnTheMap() {
        val land = Color(0xFFF2EFE9)
        listOf(MarkerColors.NEW, MarkerColors.SHORTLISTED, MarkerColors.REJECTED, MarkerColors.TAKEN, MarkerColors.NOT_CHOSEN)
            .forEach { assertTrue(ratio(Color(it), land) >= 3.0, "marker ${it.toUInt().toString(16)}: ${ratio(Color(it), land)}") }
    }

    /** The phone app's status colours are the web's tokens (web/src/styles.css), so both apps read the same. */
    @Test
    fun statusColoursMatchTheWebTokens() {
        assertEquals(Color(0xFF8A5A00), LightExtra.taken)
        assertEquals(Color(0xFF5F6B66), LightExtra.notChosen)
        assertEquals(Color(0xFFF2C265), DarkExtra.taken)
        assertEquals(Color(0xFFB4BEB9), DarkExtra.notChosen)
        assertEquals(Color(0xFF966000), LightExtra.star) // --star (S4b-BL-110)
        assertEquals(Color(0xFFF2B84B), DarkExtra.star)
        assertEquals(LightExtra.star, LightScheme.secondary)
        // The markers are the light theme's colours (the tiles stay light).
        assertEquals(LightExtra.taken, Color(MarkerColors.TAKEN))
        assertEquals(LightExtra.notChosen, Color(MarkerColors.NOT_CHOSEN))
    }
}
