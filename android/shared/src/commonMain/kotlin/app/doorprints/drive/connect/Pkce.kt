package app.doorprints.drive.connect

import app.doorprints.crypto.CryptoProvider

/**
 * PKCE (RFC 7636) with the S256 method, and the other random values of one sign-in attempt. The verifier lives only in
 * memory for the length of one attempt; Google gets only its hash.
 */
class Pkce private constructor(val verifier: String, val challenge: String, val state: String) {
    override fun toString() = "Pkce(…)"

    companion object {
        /** 32 random bytes: a 43-character verifier (RFC 7636 asks for 43 to 128). */
        fun create(p: CryptoProvider): Pkce {
            val verifier = base64Url(p.randomBytes(32))
            return Pkce(verifier, challengeOf(p, verifier), base64Url(p.randomBytes(16)))
        }

        fun challengeOf(p: CryptoProvider, verifier: String): String {
            val sha = p.sha256()
            val bytes = verifier.encodeToByteArray()
            sha.update(bytes, 0, bytes.size)
            return base64Url(sha.digest())
        }

        /** Base64url without padding (RFC 4648 §5). */
        fun base64Url(bytes: ByteArray): String =
            app.doorprints.crypto.Bytes.b64(bytes).trimEnd('=').replace('+', '-').replace('/', '_')
    }
}
