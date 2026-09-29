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

package app.doorprints.server.device;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

/** The random values of pairing (docs/03 §12.1). */
class SecretsTest {

    @Test
    void userCodesAvoidLookAlikesAndReadBackAsTyped() {
        assertThat(Secrets.CODE_ALPHABET).doesNotContain("0", "O", "1", "I", "L", "S");
        var seen = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            var code = Secrets.userCode();
            assertThat(code).hasSize(Secrets.CODE_LENGTH).matches("[" + Secrets.CODE_ALPHABET + "]+");
            seen.add(code);
            assertThat(Secrets.normalizeCode(Secrets.displayCode(code).toLowerCase())).isEqualTo(code);
            assertThat(Secrets.normalizeCode(code.substring(0, 4) + " " + code.substring(4))).isEqualTo(code);
        }
        assertThat(seen).hasSizeGreaterThan(195);
    }

    @Test
    void aTypoIsRefusedBeforeAnyLookup() {
        assertThat(Secrets.normalizeCode(null)).isNull();
        assertThat(Secrets.normalizeCode("ABCD-EFG")).isNull();
        assertThat(Secrets.normalizeCode("ABCD-EFGO")).isNull();
        assertThat(Secrets.normalizeCode("ABCD-EFGH-J")).isNull();
    }

    @Test
    void deviceKeysAreLongRandomAndRecognisable() {
        var key = Secrets.deviceKey();
        assertThat(key).startsWith("dpk_").hasSize(47).matches("dpk_[A-Za-z0-9_-]{43}");
        assertThat(Secrets.deviceKey()).isNotEqualTo(key);
        assertThat(Secrets.hash(key)).hasSize(32);
        assertThat(Secrets.last4(key)).isEqualTo(key.substring(43));
    }

    @Test
    void deviceNamesAreCleanedForTheOwnerPage() {
        assertThat(PairingService.cleanName("  Priya's\n\tPixel  ")).isEqualTo("Priya's Pixel");
        assertThat(PairingService.cleanName("")).isEqualTo("Device");
        assertThat(PairingService.cleanName("x".repeat(100))).hasSize(PairingService.MAX_NAME_LENGTH);
        assertThat(OwnerAuth.label("Mozilla/5.0 (Windows NT 10.0) Chrome/140.0 Safari/537.36")).isEqualTo("Chrome on Windows");
        assertThat(OwnerAuth.label(null)).isNull();
    }
}
