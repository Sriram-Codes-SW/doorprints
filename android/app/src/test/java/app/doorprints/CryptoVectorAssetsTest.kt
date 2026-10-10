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

package app.doorprints

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File

/**
 * The instrumented test's copy of the crypto vectors is the repository's (S4b-BL-133): `CryptoVectorsOnAndroidTest`
 * runs `hpke-vectors.json` and `dpx-vectors.json` on the emulator's provider from `app/src/androidTest/assets/crypto/`,
 * because a test APK cannot read `docs/schemas`. A change to either file without the other fails here, in the JVM
 * suite every pull request runs, not only on the emulator.
 */
class CryptoVectorAssetsTest {
    @Test
    fun theInstrumentedTestsCopiesAreTheSchemasFiles() {
        for (name in listOf("hpke-vectors.json", "dpx-vectors.json")) {
            assertArrayEquals(
                "$name differs from docs/schemas; copy it to android/app/src/androidTest/assets/crypto/",
                locate("docs/schemas/$name").readBytes(),
                locate("android/app/src/androidTest/assets/crypto/$name").readBytes(),
            )
        }
    }

    private fun locate(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("$path not found above ${File("").absolutePath}")
    }
}
