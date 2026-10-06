# iPhone CryptoProvider: design notes (S4b-BL-131)

| Field | Value |
|---|---|
| Document | Design decision, evidence and CI proof list for the iPhone's `CryptoProvider` |
| Version | 0.1 |
| Date | 2026-10-06 |
| Author | Claude (Code) |
| Status | Built; compiled for iOS on Linux, **not yet run on an iPhone or simulator** |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-10-06 | Claude (Code) | First version: the options, the choice, what was run where, what the macOS job must prove. |

## 1. What the interface needs

`CryptoProvider` (docs/15 §9.9) is small: random bytes, incremental SHA-256, HMAC-SHA-256, AES-128/256-GCM with AAD and a
caller's 96-bit nonce, P-256 key generation, key from a 32-byte scalar (with its public point), point validation and
ECDH. It has no signature primitive. HKDF, HPKE, the `dpx/1` envelope and `keys.json` are common code over it.

## 2. Evidence: what Kotlin/Native on iOS can reach

Read from the platform klibs that ship with Kotlin/Native 2.4.20 (`~/.konan/.../klib/platform/ios_simulator_arm64`), which
are generated from the iOS SDK headers; whatever is not in them cannot be called from Kotlin.

| Primitive | `platform.CoreCrypto` (CommonCrypto) | `platform.Security` |
|---|---|---|
| random | `CCRandomGenerateBytes` | `SecRandomCopyBytes` (used) |
| SHA-256 incremental | `CC_SHA256_Init/Update/Final` (used, via the existing archive tools) | |
| HMAC-SHA-256 | `CCHmac` (used) | |
| AES block cipher | `CCCrypt`, `CCCryptorCreateWithMode` (ECB, CBC, CTR; used: ECB) | |
| **AES-GCM** | **absent**: no `kCCModeGCM`, no `CCCryptorGCM*` (SPI, not in the SDK headers) | **absent** |
| P-256 generate, ECDH | | `SecKeyCreateRandomKey`, `SecKeyCopyKeyExchangeResult` with `kSecKeyAlgorithmECDHKeyExchangeStandard` (the raw x-coordinate; used) |
| P-256 key from a scalar | | `SecKeyCreateWithData` takes a private key only as `04 ‖ x ‖ y ‖ d` (Apple's documented form), so the public point must be known first |
| ECDSA | | present (`kSecKeyAlgorithmECDSASignature*`), not needed by the interface |

So option (a) alone is not enough: **GCM does not exist** in what Kotlin can call, and **a key cannot be made from a
scalar alone**. Both HPKE's DeriveKeyPair and the recovery key (docs/15 §9.4) need the second, and every message needs
the first.

## 3. Options and the choice

| Option | For | Against |
|---|---|---|
| (a) CommonCrypto and Security only | nothing of ours to maintain | impossible: no GCM, no key from a scalar |
| (b) Swift/CryptoKit bridge registered by the app | Apple's GCM and `P256.KeyAgreement.PrivateKey(rawRepresentation:)`; least own crypto | a second language and a registration step in the app (an unregistered bridge is a runtime failure, so every Kotlin test would need a fake); **none of it can be built or tested on Linux or by the shared-ios jobs, which only compile Kotlin**; the interface crossing is a Swift/Kotlin ABI surface for byte arrays |
| (c) own the two missing pieces in Kotlin, platform for the rest | fully testable on the JVM against the same known answers and against `JvmCryptoProvider`; no bridge; the iOS simulator job runs the same bytes | we own about 300 lines of crypto glue; constant-time discipline is ours to keep |

**Chosen: (c), narrowly.** The platform does every primitive it has (random, SHA-256, HMAC, the AES block, P-256
generation, ECDH). Only two things are ours, and each is small and checked against independent answers:

1. **GCM** = CommonCrypto's AES-ECB over the counter blocks and the tag mask, plus **GHASH in Kotlin** (`IosGcm.kt`). GHASH
   multiplies in GF(2¹²⁸) one bit at a time with masks: no table, no branch or index depends on H or on the data. Its inputs
   (AAD and ciphertext) are public in GCM; only H = E_K(0) is secret. The tag is verified in constant time **before** any
   keystream is applied, so a forged message never yields plaintext. No AES is implemented in Kotlin (a software AES would
   need either tables, with cache timing, or a bitsliced version; CommonCrypto's is hardware accelerated).
2. **Public point of a scalar** (`IosP256.kt`): fixed 256-step double-and-always-add ladder over the Renes-Costello-Batina
   complete projective formulas (a = −3), field elements as 16 × 16-bit limbs in `Long`s, a fixed number of reduction
   rounds, a mask-based select and a mask-based final subtraction. No branch or memory index depends on the scalar. Only
   the final inversion branches, on the public bits of p − 2. The result is checked on the curve, and after
   `SecKeyCreateWithData`, the platform's own public key for the imported key must equal it (`p256FromScalar` throws
   `INVALID_KEY` otherwise), so a disagreement between our arithmetic and Apple's cannot pass silently.

Everything fails closed: a platform call that returns an error or null is a `CryptoException` (`UNAVAILABLE` for
random/AES failures, `INVALID_KEY` for key and point failures); there is no software fallback to anything weaker, and the
old `UnavailableCryptoProvider` is gone because the provider is now complete. Keys are non-permanent `SecKey`s in memory:
Keychain, `WhenPasscodeSet`/`ThisDeviceOnly` and the Secure Enclave are the next ticket.

Limits worth knowing: GHASH costs about 128 loop steps per 16 bytes, so large photos on a phone are slower than the
JVM's (the JVM runs 100 kB in about 11 ms warm; Kotlin/Native has no JIT and has not been timed; this is a latency
question, not a correctness one). If it matters, the table-free multiplier can be replaced by
a 4-bit-window variant over masks without changing the interface.

## 4. What was run, and where

All on Linux, JDK 21, Kotlin/Native 2.4.20 (`~/.konan` was already present; the iOS compile needed no download).

| Check | Result |
|---|---|
| `:shared:compileKotlinIosSimulatorArm64`, `:shared:compileKotlinIosArm64`, `:shared:compileTestKotlinIosSimulatorArm64` with `-Pkotlin.native.enableKlibsCrossCompilation=true` | pass (the test klib includes `commonTest` and `iosTest`) |
| `PlatformCryptoProviderTest` (12) and `HpkePlatformVectorsTest` (2), common | pass **on the JVM against `JvmCryptoProvider`**; this proves the vectors (NIST GCM cases 13/14/16, RFC 4231, RFC 5903, FIPS 180 million-a, RFC 9180 A.3.1 and an AES-256 regression vector; SEC 2 point multiples; Node `crypto` for the generated ones) are right. On iOS they have **not run** |
| `IosP256Test` (7) and `IosGcmTest` (5), `iosTest` | the **same source files** were copied temporarily into the host test tree and passed on the JVM, together with a differential test (2092 scalars: 1500 random, every 2ᵏ and 2ᵏ−1, n−1…n−41, 1…40, against `JvmCryptoProvider.p256FromScalar`; 852 GCM shapes: both key sizes, 0..70 bytes, AAD 0..33 bytes, plus 100 kB, against `javax.crypto` GCM). The copy and the differential test were deleted, not committed |
| mutation gates | see the final report of the session; every mutant of the pure code is caught by a named test |

Not run, because there is no Apple SDK on Linux: **everything that calls `platform.*`** (`IosCryptoProvider`: random,
HMAC, `CCCrypt`, every `SecKey` call and the Core Foundation glue) and the `iosTest` classes themselves. Their JVM run
covers the pure arithmetic only.

## 5. What the macOS job (`shared-ios.yml`, job `ios-sim-tests`, `:shared:iosSimulatorArm64Test`) must prove

1. `PlatformCryptoProviderTest`: every known answer through the real CommonCrypto and Security.framework, in particular
   - `ecdhRfc5903` and `publicKeyOfAScalarKnownAnswers`: `SecKeyCreateWithData(04‖x‖y‖d)` accepts our `x, y`, returns the
     same public key and `SecKeyCopyKeyExchangeResult` gives the RFC 5903 secret (this is the one Apple behaviour the design
     relies on without having seen it: the documented private-key import form);
   - `generatedKeysAgreeAndAreValid`: `SecKeyCreateRandomKey` works for a non-permanent key on a simulator without Keychain
     access and its public key passes our curve check;
   - `aesGcmNistCases`, `aesGcmAcrossLengthsMatchesNode`, `aesGcmOpenFailsClosedOnEveryChange`: GCM through `CCCrypt`;
     `CCCrypt` with `kCCOptionECBMode` and a null IV must accept 16- and 32-byte keys and lengths up to 4 kB or more;
   - `hmacSha256Rfc4231` (including the 131-byte key) and `sha256KnownAnswersAndIncrementalUse`.
2. `HpkePlatformVectorsTest`: the whole HPKE path over the iPhone's primitives.
3. `IosP256Test`, `IosGcmTest`: the pure code on Kotlin/Native (arithmetic on `Long` is the same, but this is where a
   Kotlin/Native-only difference would show).
4. Not covered by anything yet, and worth a follow-up: the `dpx/1` file vectors (`docs/schemas/dpx-vectors.json`) and
   `keys.json` on the iPhone. They read repository files and are host tests; the simulator does not mount the repository.
   Embedding one dpx vector in a common test is a cheap next step.
5. A timing note: the `aesGcmAcrossLengthsMatchesNode` 4 kB case and the 1,000,000-byte SHA-256 show how slow GHASH is; if the
   simulator job's time grows noticeably, add the windowed multiplier.


## Lead's follow-up (2026-10-06)

`IosGcm.kt` (`Gcm`) and `IosP256.kt` (`P256Base`) and their tests `IosGcmTest`, `IosP256Test` are pure Kotlin (no `platform.*`), so they moved to `commonMain` and `commonTest`: they now run on every host-test run (5 and 7 tests), and the iOS compile still passes. The lead re-ran three of the mutations on them (tag check dropped, `ROUNDS` 12 to 1, and the rest of the table's classes) and each was caught by the named test. Still only provable on macOS CI: everything that calls `platform.*` (the `IosCryptoProvider` itself).


## Decision on the table-based GHASH (lead, 2026-10-06)

The review's O2 proposed a 4-bit Shoup table for GHASH (about 3x faster on the JVM, unmeasured on Kotlin/Native). The agent that built it found that the table index is a nibble of the running hash Y = (Y xor block)*H, which depends on the secret hash key H, so the premise that the index is public data does not hold: a table-driven GHASH has the usual cache-timing exposure of H. The bit-serial masked version used here has none. **Declined**: the constant-time version stays. What was kept from that work is the differential test (`GcmDifferentialTest`: the JVM's AES/GCM as reference over key sizes 16 and 32, plaintext 0..70 and 100 kB, AAD 0..33, 5000 seeded shapes, every flipped tag/ciphertext/AAD byte refused). If macOS CI measures large photos as too slow, the safer speed-up is an unrolled bit-serial multiplier (still no secret-dependent index), not a table. The rejected implementation is on the branch `feat/android-drive-f4-ghash` (not merged).
