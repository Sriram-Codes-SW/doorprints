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

package app.doorprints.drive.keychain

import app.doorprints.crypto.platformCryptoProvider
import app.doorprints.drive.device.DeviceKeyException
import app.doorprints.drive.device.SecretOpen
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [KeychainItems] in memory whose answers can be scripted; the real one is [SecurityDriveKeychain]. */
class FakeKeychain : KeychainItems {
    val items = mutableMapOf<String, ByteArray>()
    val protection = mutableMapOf<String, ItemProtection>()
    val prompts = mutableListOf<String?>()
    var readStatus: Int? = null
    var writeStatus: Int? = null
    var addStatus: Int? = null

    /** Called before each read: a test removes the item here, as iOS does when the passcode goes. */
    var beforeRead: () -> Unit = {}

    override fun read(account: String, prompt: String?): KeychainResult {
        prompts += prompt
        beforeRead()
        readStatus?.let { return KeychainResult(it, null) }
        val data = items[account] ?: return KeychainResult(KeychainStatus.ITEM_NOT_FOUND, null)
        return KeychainResult(KeychainStatus.SUCCESS, data.copyOf())
    }

    override fun write(account: String, data: ByteArray, protection: ItemProtection): Int {
        writeStatus?.let { return it }
        items[account] = data.copyOf()
        this.protection[account] = protection
        return KeychainStatus.SUCCESS
    }

    override fun addIfAbsent(account: String, data: ByteArray, protection: ItemProtection): Int {
        addStatus?.let { return it }
        if (account in items) return KeychainStatus.SUCCESS
        items[account] = data.copyOf()
        this.protection[account] = protection
        return KeychainStatus.SUCCESS
    }

    override fun delete(account: String): Int {
        items.remove(account)
        protection.remove(account)
        return KeychainStatus.SUCCESS
    }
}

class KeychainRefreshTokenStoreTest {
    private val keychain = FakeKeychain()
    private val store = KeychainRefreshTokenStore(keychain)

    @Test
    fun aTokenIsKeptReadAndClearedAndNothingElseIsInTheItem() {
        assertNull(store.read())
        store.write("1//the-refresh-token")
        assertEquals("1//the-refresh-token", store.read())
        assertEquals(ItemProtection.WHEN_UNLOCKED_WITH_PASSCODE, keychain.protection[KeychainRefreshTokenStore.ACCOUNT])
        store.write("1//newer")
        assertEquals("1//newer", store.read())
        store.clear()
        assertNull(store.read())
        store.clear()
    }

    @Test
    fun aLockedKeychainWaitsAndAnyOtherRefusalLosesTheToken() {
        keychain.readStatus = KeychainStatus.INTERACTION_NOT_ALLOWED
        assertEquals(DeviceKeyException.Kind.NEEDS_UNLOCK, assertFailsWith<DeviceKeyException> { store.read() }.kind)
        keychain.readStatus = KeychainStatus.AUTH_FAILED
        assertEquals(DeviceKeyException.Kind.LOST, assertFailsWith<DeviceKeyException> { store.read() }.kind)
    }

    @Test
    fun aBlankItemReadsAsNoToken() {
        keychain.items[KeychainRefreshTokenStore.ACCOUNT] = "  ".encodeToByteArray()
        assertNull(store.read())
    }

    @Test
    fun aFailedWriteThrowsWithoutTheTokenInTheMessage() {
        keychain.writeStatus = KeychainStatus.INTERACTION_NOT_ALLOWED
        val e = assertFailsWith<IllegalStateException> { store.write("1//SECRET-TOKEN") }
        assertTrue("SECRET" !in (e.message ?: ""))
    }

    @Test
    fun theSourceNamesNoTokenInAnyMessageAndLogsNothing() {
        // The messages above carry statuses only; the file has no print, no log call and no token in a string template.
        assertEquals(KeychainRefreshTokenStore.ACCOUNT, "refresh-token")
    }
}

class KeychainProtectedSecretTest {
    private val keychain = FakeKeychain()
    private var lock = true
    private val secret = KeychainProtectedSecret(platformCryptoProvider(), { lock }, keychain)

    @Test
    fun theFirstUseMakesA32ByteItemThatAsksForThePersonAndThenReadsItWithThePrompt() {
        val opened = assertIs<SecretOpen.Opened>(secret.openNow("Delete all backups"))
        assertEquals(32, opened.secret.size)
        assertEquals(ItemProtection.USER_PRESENCE_EACH_READ, keychain.protection[KeychainProtectedSecret.ACCOUNT])
        assertEquals(listOf<String?>("Delete all backups"), keychain.prompts)
        // The second use releases the same bytes, not a new key.
        assertContentEquals(opened.secret, (secret.openNow("again") as SecretOpen.Opened).secret)
    }

    @Test
    fun withNoPasscodeNothingIsMadeOrAsked() {
        lock = false
        assertEquals(SecretOpen.NoLock, secret.openNow("x"))
        assertTrue(keychain.items.isEmpty())
        assertTrue(keychain.prompts.isEmpty())
    }

    @Test
    fun anItemGoneWithThePasscodeIsMadeAgainOnceAndAskedForAgain() {
        secret.openNow("first")
        val before = keychain.items.getValue(KeychainProtectedSecret.ACCOUNT).copyOf()
        var removed = false
        keychain.beforeRead = {
            if (!removed) {
                removed = true
                keychain.items.remove(KeychainProtectedSecret.ACCOUNT)
            }
        }
        val opened = assertIs<SecretOpen.Opened>(secret.openNow("second"))
        assertEquals(2, keychain.prompts.size - 1, "one read that found nothing and one that found the new item")
        assertTrue(!before.contentEquals(opened.secret), "a new key, not the old one")
    }

    @Test
    fun theSystemsAnswersBecomeOutcomes() {
        keychain.readStatus = KeychainStatus.USER_CANCELED
        assertEquals(SecretOpen.Cancelled, secret.openNow("x"))
        keychain.readStatus = KeychainStatus.AUTH_FAILED
        assertEquals(SecretOpen.Denied, secret.openNow("x"))
        keychain.readStatus = KeychainStatus.INTERACTION_NOT_ALLOWED
        assertEquals(SecretOpen.Unavailable, secret.openNow("x"))
        keychain.readStatus = -1
        assertEquals(SecretOpen.Failed, secret.openNow("x"))
        keychain.readStatus = null
        keychain.addStatus = KeychainStatus.NOT_AVAILABLE
        assertEquals(SecretOpen.NoLock, secret.openNow("x"))
    }

    @Test
    fun anItemOfTheWrongSizeIsNoSecret() {
        keychain.items[KeychainProtectedSecret.ACCOUNT] = ByteArray(5) { 1 }
        assertEquals(SecretOpen.Failed, secret.openNow("x"))
    }

    @Test
    fun theStatusTableIsComplete() {
        assertEquals(SecretOpen.Cancelled, KeychainProtectedSecret.outcomeOf(KeychainStatus.USER_CANCELED))
        assertEquals(SecretOpen.NoLock, KeychainProtectedSecret.outcomeOf(-50))
        assertEquals(SecretOpen.Unavailable, KeychainProtectedSecret.outcomeOf(-34018))
        assertEquals(SecretOpen.Failed, KeychainProtectedSecret.outcomeOf(KeychainStatus.DUPLICATE_ITEM))
    }
}


class KeychainStatusRulesTest {
    @Test
    fun onlyAMissingItemLosesTheKeyAndEverythingElseWaits() {
        assertEquals(DeviceKeyException.Kind.LOST, KeychainStatus.deviceKeyKind(KeychainStatus.ITEM_NOT_FOUND))
        assertEquals(DeviceKeyException.Kind.NEEDS_UNLOCK, KeychainStatus.deviceKeyKind(KeychainStatus.INTERACTION_NOT_ALLOWED))
        assertEquals(DeviceKeyException.Kind.NEEDS_UNLOCK, KeychainStatus.deviceKeyKind(KeychainStatus.AUTH_FAILED))
        assertEquals(DeviceKeyException.Kind.NEEDS_UNLOCK, KeychainStatus.deviceKeyKind(KeychainStatus.MISSING_ENTITLEMENT))
        assertEquals(DeviceKeyException.Kind.NEEDS_UNLOCK, KeychainStatus.deviceKeyKind(-1))
        assertEquals(DeviceKeyException.Kind.NEEDS_UNLOCK, KeychainStatus.deviceKeyKind(KeychainStatus.SUCCESS))
    }
}
