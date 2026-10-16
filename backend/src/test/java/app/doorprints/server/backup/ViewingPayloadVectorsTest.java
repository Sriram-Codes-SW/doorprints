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

package app.doorprints.server.backup;

import app.doorprints.server.record.Record;
import app.doorprints.server.record.RecordKey;
import app.doorprints.server.record.ViewingPayload;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared viewing payload vectors ({@code docs/schemas/viewing-payload-vectors.json}, S4b-BL-247) on the server: the
 * candidate a stored payload names ({@code candidateId}, or {@code houseId} written before; both keys a bad row), the
 * backup export's row of it, and the payload an import writes for that row, byte for byte as the website and the phone
 * write it. The twins are the website's {@code viewing-payload-vectors.spec.ts} and Android's
 * {@code ViewingPayloadVectorsTest}. docs/06 TC-U-208.
 */
class ViewingPayloadVectorsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void everyStoredPayloadNamesTheVectorsCandidateAndIsWrittenBackWithCandidateId() throws Exception {
        var vectors = JSON.readTree(repoFile("docs/schemas/viewing-payload-vectors.json"));
        assertThat(vectors.get("format").asString()).isEqualTo("doorprints-viewing-payload-vectors/1");
        int read = 0;
        int old = 0;
        for (JsonNode c : vectors.get("reads")) {
            var name = c.get("name").asString();
            var expected = c.get("candidateId").isNull() ? null : c.get("candidateId").asString();
            var payload = c.get("payload");
            if (expected != null) assertThat(ViewingPayload.candidateOf(payload)).as(name).isEqualTo(expected);

            var record = new Record(new RecordKey("viewing", c.get("id").asString()));
            record.setPayload(payload.toString());
            record.setUpdatedAt(Instant.ofEpochMilli(1_790_000_000_000L));
            var row = BackupMapper.viewing(record, JSON);
            if (expected == null) {
                assertThat(row).as(name).isNull();
                continue;
            }
            assertThat(row).as(name).isNotNull();
            assertThat(row.houseId()).as(name).isEqualTo(expected);
            assertThat(row.toPayload(JSON)).as(name).isEqualTo(c.get("written").asString());
            read++;
            if (payload.has(ViewingPayload.OLD_KEY)) old++;
        }
        assertThat(read).as("cases read").isGreaterThanOrEqualTo(4);
        assertThat(old).as("cases of the key before S4b-BL-247 read").isGreaterThanOrEqualTo(2);
    }

    @Test
    void anImportWritesOnlyCandidateId() {
        var row = new BackupViewing("v_1", "h1", 1_790_000_000_000L, null, null, null, null, null, null, null, null, 1L);
        assertThat(row.toPayload(JSON)).isEqualTo("{\"candidateId\":\"h1\",\"startsAt\":1790000000000,"
                + "\"durationMin\":30,\"kind\":\"FIRST\",\"status\":\"PLANNED\",\"remindMin\":60}");
    }

    private static String repoFile(String relative) throws Exception {
        var dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParent()) {
            var file = dir.resolve(relative);
            if (Files.isRegularFile(file)) return Files.readString(file);
        }
        throw new IllegalStateException(relative + " not found");
    }
}
