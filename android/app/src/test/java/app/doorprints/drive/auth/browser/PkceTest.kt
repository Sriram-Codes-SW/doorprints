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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Random

class PkceTest {
    @Test
    fun challengeMatchesRfc7636AppendixB() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun verifierIsInTheRfcRangeAndAlphabet() {
        val v = Pkce.newVerifier(SecureRandom())
        assertTrue(v.length in 43..128)
        assertTrue(Pkce.isValidVerifier(v))
        assertTrue(v.none { it == '=' || it == '+' || it == '/' })
    }

    @Test
    fun verifiersAreNotRepeated() {
        val r = SecureRandom()
        assertEquals(50, (1..50).map { Pkce.newVerifier(r) }.toSet().size)
    }

    @Test
    fun verifierAndStateComeFromTheGivenRandom() {
        val zero = object : Random() {
            override fun nextBytes(bytes: ByteArray) = bytes.fill(0)
        }
        assertEquals("A".repeat(43), Pkce.newVerifier(zero))
        assertEquals("A".repeat(32), Pkce.newState(zero))
    }

    @Test
    fun stateDiffersFromVerifierEntropyAndIsLongEnough() {
        val r = SecureRandom()
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
