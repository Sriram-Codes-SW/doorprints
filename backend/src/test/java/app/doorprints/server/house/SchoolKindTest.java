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
package app.doorprints.server.house;

import app.doorprints.server.backup.BackupData;
import app.doorprints.server.backup.BackupWire;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hidden schools kind (kinds PR 5, S4b-BL-209; docs/03 ADR-36 §18.9) on the server, without a database: the server
 * keeps a school as it came. Its extras are ids the house kind does not know, each a value of an extras type, so
 * {@link CandidateExtras} keeps them (S4b-BL-248's rules); its kind is any id of the pattern; and a kind other than the
 * house may give its own money any cadence ({@link HouseDto#isMoneyOfTheKind}), so nothing in the server's validation
 * had to change. Reads {@code docs/schemas/kinds/school-vectors.json}, as the website's {@code school-kind.spec.ts} and
 * the phone's {@code SchoolSampleTest} do. With PostGIS: {@code SchoolCandidateApiTest}.
 */
class SchoolKindTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode vectors() throws Exception {
        var dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParent()) {
            var file = dir.resolve("docs/schemas/kinds/school-vectors.json");
            if (Files.isRegularFile(file)) return JSON.readTree(Files.readString(file));
        }
        throw new IllegalStateException("docs/schemas/kinds/school-vectors.json not found");
    }

    /** A backup's school is read into the import's rows and written back byte for byte, as on the website and the phone. */
    @Test
    void aSchoolInABackupIsReadAndWrittenBackByteForByte() throws Exception {
        var backup = vectors().get("backup");
        var data = JSON.treeToValue(BackupWire.legacyOf(backup), BackupData.class);
        var written = BackupWire.currentOf(JSON.valueToTree(data));
        assertThat(JSON.writeValueAsString(written.get("candidates"))).isEqualTo(JSON.writeValueAsString(backup.get("candidates")));
    }

    /** The school on the wire passes the sync API's validation, and its kind and extras are what the server keeps. */
    @Test
    void aSchoolOnTheWireIsValidAndKeptAsItCame() throws Exception {
        var wire = (ObjectNode) vectors().get("candidate").deepCopy();
        var dto = JSON.treeToValue(wire, HouseDto.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(dto)).isEmpty();
        }
        var house = new House();
        dto.applyTo(house);
        assertThat(house.getKind()).isEqualTo("school");
        assertThat(JSON.readTree(house.getExtras())).isEqualTo(wire.get("extras"));
        assertThat(dto.money()).isNull();
    }

    /** A school's own money may be paid yearly; a house's may not (a rent or a sale, door 7). */
    @Test
    void aSchoolsMoneyMayHaveAnyCadenceOfTheVocabularyAndAHousesMayNot() throws Exception {
        var wire = (ObjectNode) vectors().get("candidate").deepCopy();
        wire.set("money", JSON.readTree("{\"amount\":96000,\"cadence\":\"year\"}"));
        assertThat(JSON.treeToValue(wire, HouseDto.class).isMoneyOfTheKind()).isTrue();
        wire.put("kind", "house");
        assertThat(JSON.treeToValue(wire, HouseDto.class).isMoneyOfTheKind()).isFalse();
    }
}
