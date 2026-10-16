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

package app.doorprints.drive.auth.browser

import app.doorprints.crypto.platformCryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PkceTest {
    @Test
    fun challengeMatchesRfc7636AppendixB() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun verifierIsInTheRfcRangeAndAlphabet() {
        val v = Pkce.newVerifier(platformCryptoProvider()::randomBytes)
        assertTrue(v.length in 43..128)
        assertTrue(Pkce.isValidVerifier(v))
        assertTrue(v.none { it == '=' || it == '+' || it == '/' })
    }

    @Test
    fun verifiersAreNotRepeated() {
        val r = platformCryptoProvider()::randomBytes
        assertEquals(50, (1..50).map { Pkce.newVerifier(r) }.toSet().size)
    }

    @Test
    fun verifierAndStateComeFromTheGivenRandom() {
        val zero: RandomBytes = { size -> ByteArray(size) }
        assertEquals("A".repeat(43), Pkce.newVerifier(zero))
        assertEquals("A".repeat(32), Pkce.newState(zero))
    }

    @Test
    fun aSourceThatGivesTheWrongNumberOfBytesIsRefused() {
        val short: RandomBytes = { size -> ByteArray(size - 1) }
        assertFailsWith<IllegalArgumentException> { Pkce.newVerifier(short) }
        assertFailsWith<IllegalArgumentException> { Pkce.newState(short) }
    }

    @Test
    fun theChallengeIsTheUnpaddedBase64UrlOfTheHash() {
        // 43 characters, no padding, no "+" or "/": SHA-256 is 32 bytes.
        val c = Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertEquals(43, c.length)
        assertTrue(c.none { it == '=' || it == '+' || it == '/' })
    }

    @Test
    fun stateDiffersFromVerifierEntropyAndIsLongEnough() {
        val r = platformCryptoProvider()::randomBytes
        assertTrue(Pkce.newState(r).length >= 32)
        assertNotEquals(Pkce.newState(r), Pkce.newState(r))
    }

    @Test
    fun validatorRefusesShortLongAndForeignCharacters() {
        assertTrue(!Pkce.isValidVerifier("a".repeat(42)))
        assertTrue(!Pkce.isValidVerifier("a".repeat(129)))
        assertTrue(Pkce.isValidVerifier("a".repeat(128)))
        assertTrue(!Pkce.isValidVerifier("a".repeat(42) + "="))
    }
}
