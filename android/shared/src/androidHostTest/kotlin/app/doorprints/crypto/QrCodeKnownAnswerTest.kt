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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Known answers for the QR encoder (S4b-BL-126), made by the website's own `encodeQr` (`qr-code.ts`) over the pattern
 * payload byte i = (31 · i + 7) mod 256: the symbol's side and the first 16 bytes of the SHA-256 of its rows, each row
 * `0`/`1` text joined by a line feed. Every version 1 to 10 appears at its smallest and largest payload, so the block
 * structure, the Reed-Solomon codewords, the alignment patterns and the version words are all pinned.
 */
class QrCodeKnownAnswerTest {
    private val p = JvmCryptoProvider

    private fun digestOf(m: Array<BooleanArray>): String {
        val text = m.joinToString("\n") { r -> r.joinToString("") { if (it) "1" else "0" } }
        return Bytes.hex(p.sha256Of(text.encodeToByteArray())).substring(0, 32)
    }

    @Test
    fun everyVersionMatchesTheWebsitesEncoderModuleForModule() {
        val answers = listOf(
            Triple(1, 21, "e28946b9fd1c59bc55478b0fa7a4ec5a"),
            Triple(17, 25, "94f3fd16a872719967ff1cd965d3df88"),
            Triple(18, 25, "608784b3f620eb29c633f82f7c36ee79"),
            Triple(32, 29, "d5439d30bea2ec43fc1b881a47716473"),
            Triple(33, 29, "fb23e3f9b5ff3ef0fa9032c31553c946"),
            Triple(53, 33, "1ab1080162e6a2555c773dd11042cf82"),
            Triple(54, 33, "b08650e5e8a59e6189bdc3a5a1c60c0e"),
            Triple(78, 37, "c02f99c0e7f8c3b7477615614ab749d9"),
            Triple(79, 37, "6b3a9b5addcfb6775ddd36e6c5c41133"),
            Triple(106, 41, "7a8208e903cf78a34c6a0b8be0ebfeb6"),
            Triple(107, 45, "2098eb6d1245436a5efea09d5905dd17"),
            Triple(134, 49, "52123ae0bacb05813e87385e76b744ca"),
            Triple(150, 49, "53a8ec296403b154df60d216e71fdb66"),
            Triple(151, 49, "ee2e7f0df42071e0086473108f53da8f"),
            Triple(174, 53, "31dfcaa1b7089b1ab2bd2ac75afe211a"),
            Triple(175, 53, "1c7870612bf42080ecf099978cfacbd4"),
            Triple(213, 57, "61ea84dfb2678dfcfe3cdeb7f4f3c8b1"),
        )
        for ((length, side, digest) in answers) {
            val m = encodeQr(patternBytes(length))
            assertEquals("side for $length bytes", side, m.size)
            assertEquals("modules for $length bytes", digest, digestOf(m))
        }
    }

    @Test
    fun theSvgOfTheEnrolmentSizedPayloadIsTheWebsitesSvg() {
        val svg = qrSvg(patternBytes(134))
        assertEquals("29d786d999c7cfe4f262ab9dc2d21761", Bytes.hex(p.sha256Of(svg.encodeToByteArray())).substring(0, 32))
    }
}
