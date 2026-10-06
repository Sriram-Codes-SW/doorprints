package app.doorprints.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * GHASH and the GCM composition of the iOS provider (S4b-BL-131). The block cipher here is a stub that records its
 * calls, so what is tested is exactly the code the iPhone adds on top of CommonCrypto: the counter blocks, the tag mask,
 * GHASH, and the order (tag first). Real AES is covered by `PlatformCryptoProviderTest` and by Node/NIST vectors.
 */
class IosGcmTest {
    private fun h(s: String) = Bytes.unhex(s)

    @Test
    fun ghashMatchesTheGcmSpecification() {
        // Test case 2 of the GCM specification: H = E_K(0) for K = 0, C = one block, no AAD. GHASH = tag xor E_K(J0).
        assertEquals(
            "f38cbb1ad69223dcc3457ae5b6b0f885",
            Bytes.hex(Gcm.ghash(h("66e94bd4ef8a2c3b884cfa59ca342b2e"), ByteArray(0), h("0388dace60b6a392f328c2b971b2fe78"))),
        )
        // Test case 16 (AES-256, AAD and a partial last block): GHASH = T xor E_K(J0), computed with Node's AES-ECB.
        assertEquals(
            "8bd0c4d8aacd391e67cca447e8c38f65",
            Bytes.hex(
                Gcm.ghash(
                    h("acbef20579b4b8ebce889bac8732dad7"),
                    h("feedfacedeadbeeffeedfacedeadbeefabaddad2"),
                    h(
                        "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                            "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662",
                    ),
                ),
            ),
        )
    }

    @Test
    fun ghashOfNothingIsZero() {
        // Only the length block remains, and it is all zero, so Y stays 0.
        assertContentEquals(ByteArray(16), Gcm.ghash(ByteArray(16) { 7 }, ByteArray(0), ByteArray(0)))
    }

    @Test
    fun theCounterBlocksAreNonceThenCounterFromTwoAndTheTagMaskUsesCounterOne() {
        val seen = mutableListOf<ByteArray>()
        val gcm = Gcm { blocks ->
            seen += blocks.copyOf()
            ByteArray(blocks.size) { 0 } // a cipher whose output is all zero: keystream 0, mask 0, H = 0
        }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val sealed = gcm.seal(nonce, ByteArray(0), ByteArray(40) { it.toByte() })
        assertEquals(40 + 16, sealed.size)
        // With a zero keystream the ciphertext is the plaintext, and with H = 0 and a zero mask the tag is zero.
        assertContentEquals(ByteArray(40) { it.toByte() }, sealed.copyOf(40))
        assertContentEquals(ByteArray(16), sealed.copyOfRange(40, 56))
        // Calls: H (zero block), the three counter blocks (2, 3, 4) in one call, then J0 (counter 1).
        assertContentEquals(ByteArray(16), seen[0])
        assertEquals(48, seen[1].size)
        for (b in 0 until 3) {
            assertContentEquals(nonce, seen[1].copyOfRange(b * 16, b * 16 + 12))
            assertContentEquals(byteArrayOf(0, 0, 0, (b + 2).toByte()), seen[1].copyOfRange(b * 16 + 12, b * 16 + 16))
        }
        assertContentEquals(nonce + byteArrayOf(0, 0, 0, 1), seen.last())
    }

    @Test
    fun openChecksTheTagBeforeItAppliesTheKeystream() {
        val calls = mutableListOf<Int>()
        val gcm = Gcm { blocks ->
            calls += blocks.size
            ByteArray(blocks.size) { 1 }
        }
        val nonce = ByteArray(12)
        val sealed = gcm.seal(nonce, ByteArray(3), ByteArray(33))
        calls.clear()
        gcm.open(nonce, ByteArray(3), sealed)
        // H, then the tag mask, then the keystream: the counter blocks (3 of them = 48 bytes) come last.
        assertEquals(listOf(16, 16, 48), calls)
        calls.clear()
        val bad = sealed.copyOf().also { it[sealed.size - 1] = (it[sealed.size - 1].toInt() xor 1).toByte() }
        val failure = assertFailsWith<CryptoException> { gcm.open(nonce, ByteArray(3), bad) }
        assertEquals(CryptoException.Kind.AUTH_FAILED, failure.kind)
        assertEquals(listOf(16, 16), calls) // no keystream was produced for the forged message
    }

    @Test
    fun nonceAndSealedLengthsAreChecked() {
        val gcm = Gcm { ByteArray(it.size) }
        for (n in listOf(0, 11, 13, 16)) {
            for (op in listOf({ gcm.seal(ByteArray(n), ByteArray(0), ByteArray(1)) }, { gcm.open(ByteArray(n), ByteArray(0), ByteArray(32)) })) {
                assertEquals(CryptoException.Kind.INVALID_INPUT, assertFailsWith<CryptoException> { op() }.kind)
            }
        }
        for (n in listOf(0, 15)) {
            val failure = assertFailsWith<CryptoException> { gcm.open(ByteArray(12), ByteArray(0), ByteArray(n)) }
            assertEquals(CryptoException.Kind.AUTH_FAILED, failure.kind)
        }
    }
}
