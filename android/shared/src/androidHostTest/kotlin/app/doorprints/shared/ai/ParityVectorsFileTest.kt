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

package app.doorprints.shared.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The common tests' copy of the AI test vectors is the file's current text (run `.github/scripts/parity-vectors-kotlin.py`). */
class ParityVectorsFileTest {
    @Test
    fun theCopyMatchesTheFile() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "docs/ai/evals/parity-vectors.json") }.first { it.isFile }
        assertEquals(file.readText(), PARITY_VECTORS_JSON, "run .github/scripts/parity-vectors-kotlin.py")
    }
}
