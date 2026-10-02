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

/**
 * What a folder key (docs/15 §9.1: random, 256 bits, one per epoch) is used for. The folder key itself is only ever
 * the input of HKDF-SHA-256 (empty salt, a distinct `info` per use), never an AES or HMAC key directly, so each use
 * has its own key:
 *
 * - `doorprints/dpx1/dir`: the MAC of `keys.json` (docs/15 §9.3);
 * - `doorprints/dpx1/content-wrap`: the AES-256-GCM wrap of a file's content key (§9.6);
 * - `doorprints/dpx1/chain-wrap`: the AES-256-GCM wrap of epoch N − 1's folder key under epoch N's (§9.1).
 */
internal object FolderKey {
    const val SIZE = 32
    private const val DIR = "doorprints/dpx1/dir"
    private const val CONTENT_WRAP = "doorprints/dpx1/content-wrap"
    private const val CHAIN_WRAP = "doorprints/dpx1/chain-wrap"

    fun macKey(p: CryptoProvider, folderKey: ByteArray): ByteArray = derive(p, folderKey, DIR)
    fun contentWrapKey(p: CryptoProvider, folderKey: ByteArray): AesKey = p.aesKey(derive(p, folderKey, CONTENT_WRAP))
    fun chainWrapKey(p: CryptoProvider, folderKey: ByteArray): AesKey = p.aesKey(derive(p, folderKey, CHAIN_WRAP))

    private fun derive(p: CryptoProvider, folderKey: ByteArray, info: String): ByteArray {
        require(folderKey.size == SIZE) { "a folder key is 32 bytes" }
        return Hkdf(p).derive(ByteArray(0), folderKey, Bytes.utf8(info), 32)
    }
}

/**
 * The additional data of every wrap (docs/15 §9.2: `epoch ‖ kid ‖ purpose ‖ inner`), each field fixed-size or
 * length-prefixed so no two different inputs give the same bytes. The purpose comes first, as a label.
 */
internal object WrapAad {
    /** A file's content key under the folder key of [epoch], written by [writerKid], holding [inner]. */
    fun contentKey(epoch: Int, writerKid: ByteArray, inner: String): ByteArray {
        require(writerKid.size == KID_SIZE)
        return Bytes.concat(Bytes.label("dpx1/content-key"), Bytes.u32(epoch.toLong()), writerKid, Bytes.label(inner))
    }

    /** The folder key of [epoch], HPKE-wrapped for the recipient [recipientKid] (a device or the recovery key). */
    fun folderKey(epoch: Int, recipientKid: ByteArray): ByteArray {
        require(recipientKid.size == KID_SIZE)
        return Bytes.concat(Bytes.label("dpx1/folder-key"), Bytes.u32(epoch.toLong()), recipientKid)
    }

    /** The folder key of epoch − 1 under the folder key of [epoch] (docs/15 §9.1: AAD the two epoch numbers). */
    fun chain(epoch: Int): ByteArray =
        Bytes.concat(Bytes.label("dpx1/epoch-chain"), Bytes.u32(epoch.toLong()), Bytes.u32(epoch.toLong() - 1))

    const val KID_SIZE = 16

    /** HPKE's `info` for every folder-key wrap (docs/15 §9.2). */
    val HPKE_INFO: ByteArray = Bytes.utf8("doorprints/dpx1/wrap")
}

/** `kid` = the first 16 bytes of the SHA-256 of an uncompressed public key (docs/15 §9.3). */
fun kidOf(p: CryptoProvider, publicKey: ByteArray): ByteArray = p.sha256Of(publicKey).copyOfRange(0, WrapAad.KID_SIZE)

/** The folder keys a reader holds, by epoch; null for an epoch it does not have. */
fun interface FolderKeys {
    fun folderKey(epoch: Int): ByteArray?
}
