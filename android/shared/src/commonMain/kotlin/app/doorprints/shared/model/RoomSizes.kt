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

package app.doorprints.shared.model

import kotlin.math.floor

/**
 * Room sizes between the stored centimetres and what a person reads and types (docs/11 5.6, slice 1c): feet and inches
 * or metres, and areas in sq ft or m². The TypeScript twin is `web/src/app/shared/room-sizes.ts`; `RoomSizesTest` and
 * `room-sizes.spec.ts` keep the same vectors (13 ft 0 in = 396 cm, 12 ft 0 in = 366 cm, 396 x 366 cm = 156 sq ft,
 * 3.96 m x 3.66 m = 14.5 m²). Every rounding is half up (`floor(x + 0.5)`), JavaScript's `Math.round` for these
 * non-negative values, never `kotlin.math.round`, which rounds ties to even on the JVM.
 */
object RoomSizes {
    const val CM_PER_INCH = 2.54
    const val SQ_CM_PER_SQ_FT = 929.0304

    /** A length as whole feet and the inches left over (0 to 11). */
    data class FeetInches(val feet: Int, val inches: Int)

    /** The nearest whole inch, as feet and the inches left: 396 cm is 13 ft 0 in. */
    fun cmToFeetInches(cm: Int): FeetInches {
        val inches = half(cm / CM_PER_INCH).toInt()
        return FeetInches(inches / 12, inches % 12)
    }

    /** The nearest whole centimetre: 13 ft 0 in is 396 cm. */
    fun feetInchesToCm(feet: Int, inches: Int): Int = half((feet * 12 + inches) * CM_PER_INCH).toInt()

    /** Whole square feet: 396 x 366 cm is 156 sq ft (155.99 rounds up). */
    fun areaSqFt(lengthCm: Int, widthCm: Int): Long = sqFt(lengthCm.toLong() * widthCm)

    /** Square metres to one decimal: 396 x 366 cm is 14.5 m². */
    fun areaSqM(lengthCm: Int, widthCm: Int): Double = sqM(lengthCm.toLong() * widthCm)

    /** Whole square feet of an area in square centimetres (a total). */
    fun sqFt(sqCm: Long): Long = half(sqCm / SQ_CM_PER_SQ_FT).toLong()

    /** Square metres to one decimal of an area in square centimetres. */
    fun sqM(sqCm: Long): Double = half(sqCm / 1000.0) / 10

    /** `13 ft 0 in`: the house form, Compare, the readable copies and AI (always this form). */
    fun feetInchesText(cm: Int): String = cmToFeetInches(cm).let { "${it.feet} ft ${it.inches} in" }

    /** Metres with two decimals, the centimetre shown: `3.96` (for `3.96 m`). */
    fun metresText(cm: Int): String = "${cm / 100}.${(cm % 100).toString().padStart(2, '0')}"

    /** One size in [unit]: `13 ft 0 in` or `3.96 m`. */
    fun lengthText(cm: Int, unit: LengthUnit): String =
        if (unit == LengthUnit.M) metresText(cm) + " m" else feetInchesText(cm)

    /** `13 ft 0 in × 12 ft 0 in`, or null unless both sizes are known. */
    fun sizeText(room: HouseRoom, unit: LengthUnit): String? {
        val l = room.lengthCm ?: return null
        val w = room.widthCm ?: return null
        return lengthText(l, unit) + " × " + lengthText(w, unit)
    }

    /** An area's number in [unit], without the unit: `156` (sq ft) or `14.5` (m²). */
    fun areaNumber(sqCm: Long, unit: LengthUnit): String =
        if (unit == LengthUnit.M) oneDecimal(sqM(sqCm)) else sqFt(sqCm).toString()

    /** `156 sq ft` or `14.5 m²`. */
    fun areaText(sqCm: Long, unit: LengthUnit): String =
        areaNumber(sqCm, unit) + if (unit == LengthUnit.M) " m²" else " sq ft"

    /**
     * Metres as typed in the form (`3.96`, `3,96`, `4`), as whole centimetres within 0..[HouseRooms.MAX_CM]; null
     * for anything else (blank included).
     */
    fun parseMetres(text: String): Int? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty() || !METRES.matches(t)) return null
        return HouseRooms.cm(half(t.toDouble() * 100).toInt())
    }

    /** Feet and inches as typed (blank is 0, inches 0..11), as whole centimetres within range; null otherwise. */
    fun parseFeetInches(feet: String, inches: String): Int? {
        val f = feet.trim().ifEmpty { "0" }.toIntOrNull() ?: return null
        val i = inches.trim().ifEmpty { "0" }.toIntOrNull() ?: return null
        if (f < 0 || i !in 0..11 || f > 200) return null
        return HouseRooms.cm(feetInchesToCm(f, i))
    }

    private val METRES = Regex("""\d{1,2}(\.\d{0,2})?""")

    private fun half(x: Double): Double = floor(x + 0.5)

    private fun oneDecimal(value: Double): String {
        val tenths = half(value * 10).toLong()
        return "${tenths / 10}.${tenths % 10}"
    }
}
