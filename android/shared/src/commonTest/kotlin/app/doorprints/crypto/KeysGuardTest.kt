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
import kotlin.test.assertNull

/**
 * The pure rules with no primitive under them, so they also run on the iPhone simulator (TC-U-131): the rollback
 * watermark, the revoked-epoch rule and the recovery key's text.
 */
class KeysGuardTest {
    private class Store(var value: KeysWatermark? = null) : KeysWatermarkStore {
        var saves = 0
        override fun load() = value
        override fun save(watermark: KeysWatermark) {
            value = watermark
            saves++
        }
    }

    @Test
    fun theWatermarkOnlyMovesUp() {
        val store = Store()
        val g = KeysGuard(store)
        g.accept(1, 1)
        g.accept(1, 3)
        g.accept(2, 4)
        assertEquals(KeysWatermark(2, 4), store.value)
        assertEquals(KeysException.Kind.ROLLED_BACK, assertFailsWith<KeysException> { g.accept(2, 3) }.kind)
        assertEquals(KeysException.Kind.ROLLED_BACK, assertFailsWith<KeysException> { g.accept(1, 9) }.kind)
        assertEquals(KeysException.Kind.WATERMARK_CONFLICT, assertFailsWith<KeysException> { g.accept(3, 4) }.kind)
        val before = store.saves
        g.accept(2, 4)
        assertEquals(before, store.saves)
        assertEquals(KeysWatermark(2, 4), store.value)
    }

    @Test
    fun checkChangesNothing() {
        val store = Store()
        KeysGuard(store).check(5, 5)
        assertNull(store.value)
    }

    private fun kid(b: Int) = ByteArray(16) { b.toByte() }

    private fun body(): KeysBody {
        val w = HpkeWrap(ByteArray(65), ByteArray(48))
        return KeysBody(
            revision = 7,
            epoch = 3,
            chain = emptyList(),
            devices = listOf(DeviceEntry(kid(1), "Phone", DevicePlatform.ANDROID, ByteArray(65), 0, null, w)),
            recovery = null,
            revoked = listOf(RevokedEntry(kid(2), 1000, 2), RevokedEntry(kid(3), 5000, 3)),
        )
    }

    @Test
    fun revokedEpochRule() {
        val b = body()
        val v = RevokedEpochRule
        // The device revoked at 1000 (epoch 2): its old files stay, anything later or newer is skipped.
        assertEquals(RevokedEpochRule.Verdict.ACCEPT, v.check(b, 1, kid(2), 999))
        assertEquals(RevokedEpochRule.Verdict.SKIP_REVOKED_WRITER, v.check(b, 1, kid(2), 1001))
        assertEquals(RevokedEpochRule.Verdict.SKIP_REVOKED_WRITER, v.check(b, 2, kid(2), 10))
        // A listed device writing under epoch 1 after the first revoke, or under epoch 2 after the second.
        assertEquals(RevokedEpochRule.Verdict.SKIP_OLD_EPOCH_AFTER_REVOKE, v.check(b, 1, kid(1), 1001))
        assertEquals(RevokedEpochRule.Verdict.SKIP_OLD_EPOCH_AFTER_REVOKE, v.check(b, 2, kid(1), 5001))
        assertEquals(RevokedEpochRule.Verdict.ACCEPT, v.check(b, 2, kid(1), 4999))
        assertEquals(RevokedEpochRule.Verdict.ACCEPT, v.check(b, 3, kid(1), 9999))
        assertEquals(RevokedEpochRule.Verdict.SKIP_UNKNOWN_WRITER, v.check(b, 3, kid(9), 0))
        assertEquals(RevokedEpochRule.Verdict.NEWER_EPOCH, v.check(b, 4, kid(1), 0))
    }

    @Test
    fun recoveryKeyText() {
        val key = RecoveryKey.fromBytes(ByteArray(16) { (it * 17).toByte() })
        assertEquals(27, key.symbols.length)
        assertEquals(key.symbols, RecoveryKey.parse(key.display.lowercase().replace('0', 'o')).symbols)
        val e = assertFailsWith<RecoveryKeyException> { RecoveryKey.parse(key.symbols.dropLast(1) + if (key.symbols.last() == '0') "1" else "0") }
        assertEquals(RecoveryKeyException.Reason.CHECK_MISMATCH, e.reason)
        assertEquals(RecoveryKeyException.Reason.WRONG_LENGTH, assertFailsWith<RecoveryKeyException> { RecoveryKey.parse("ABCD") }.reason)
        assertEquals(RecoveryKeyException.Reason.INVALID_CHARACTER, assertFailsWith<RecoveryKeyException> { RecoveryKey.parse("é" + key.symbols.drop(1)) }.reason)
        assertEquals(RecoveryKeyException.Reason.OUT_OF_RANGE, assertFailsWith<RecoveryKeyException> { RecoveryKey.parse("Z" + key.symbols.drop(1)) }.reason)
    }
}
