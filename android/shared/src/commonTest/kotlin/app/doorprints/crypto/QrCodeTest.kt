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

package app.doorprints.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The QR encoder's pure checks (S4b-BL-126; web twin: `qr-code.spec.ts`, same expectations): the GF(2^8) table, the
 * published version words, the finder, format and dark-module cells, and the version the enrolment payload needs. The
 * full known answers (a digest of every module for payloads from 1 to 213 bytes, made by the website's encoder) are in
 * `QrCodeKnownAnswerTest`.
 */
class QrCodeTest {
    private fun rows(m: Array<BooleanArray>) = m.map { r -> r.joinToString("") { if (it) "1" else "0" } }

    @Test
    fun usesTheGfTableAndThePublishedVersionInfoWords() {
        assertEquals(0x1d, QR_GF_EXP8)
        assertEquals(0x07c94, qrVersionBits(7))
        assertEquals(0x085bc, qrVersionBits(8))
        assertEquals(0x09a99, qrVersionBits(9))
        assertEquals(0x0a4d3, qrVersionBits(10))
    }

    @Test
    fun drawsFindersTheDarkModuleAndFormatBitsForEccMMask0() {
        val m = encodeQr("A".encodeToByteArray())
        assertEquals(21, m.size)
        assertTrue(m[0][0])
        assertFalse(m[1][1])
        assertTrue(m[3][3])
        assertFalse(m[7][7])
        assertTrue(m[0][20])
        assertTrue(m[20][0])
        assertTrue(m[13][8])
        assertFalse(m[0][8])
        assertTrue(m[1][8])
        assertFalse(m[8][20])
    }

    @Test
    fun theWholeSymbolForOneByteIsTheWebsitesSymbol() {
        assertEquals(
            listOf(
                "111111100111001111111", "100000101011101000001", "101110100010001011101", "101110100100001011101",
                "101110101100101011101", "100000100010101000001", "111111101010101111111", "000000000001100000000",
                "101010100011000010010", "100111000100001000110", "010000101110100010001", "101010000110001000100",
                "101001101010101010101", "000000001111010101010", "111111100001011101111", "100000100101110111000",
                "101110101101011101101", "101110100000001000110", "101110101100100010001", "100000100110001000110",
                "111111101000101010111",
            ),
            rows(encodeQr("A".encodeToByteArray())),
        )
    }

    @Test
    fun growsToVersion8ForAnEnrolmentSizedPayloadAndEmitsAnSvg() {
        val payload = ByteArray(134)
        payload[0] = 0x64
        val m = encodeQr(payload)
        assertEquals(21 + 4 * 7, m.size)
        assertTrue(m[m.size - 8][8])
        val svg = qrSvg(payload)
        assertTrue(svg.startsWith("<svg "))
        assertTrue(svg.contains("<rect "))
        // Four quiet modules each side: 49 + 8.
        assertTrue(svg.contains("viewBox=\"0 0 57 57\""))
    }

    @Test
    fun theSizeStepsAtEachVersionsByteCapacityAndRefusesWhatVersion10CannotHold() {
        // ECC M byte-mode capacities of versions 1..10: 14, 26, 42, 62, 84, 106, 122, 152, 180, 213.
        val capacity = intArrayOf(14, 26, 42, 62, 84, 106, 122, 152, 180, 213)
        for (v in 1..10) {
            assertEquals(21 + 4 * (v - 1), encodeQr(ByteArray(capacity[v - 1])).size, "version $v holds ${capacity[v - 1]}")
            if (v < 10) assertEquals(21 + 4 * v, encodeQr(ByteArray(capacity[v - 1] + 1)).size, "version ${v + 1} starts at ${capacity[v - 1] + 1}")
        }
        assertFailsWith<IllegalArgumentException> { encodeQr(ByteArray(214)) }
    }

    @Test
    fun theOfferTextOfTheEnrolmentCodeFitsAndIsOneVersion8Symbol() {
        // "dp1." + 130 base64url characters = 134 bytes (65 + 32 = 97 bytes, 130 characters, no padding).
        val text = ByteArray(4 + 130) { 'x'.code.toByte() }
        assertEquals(49, encodeQr(text).size)
    }
}
