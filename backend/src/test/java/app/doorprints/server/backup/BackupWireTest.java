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

import app.doorprints.server.common.BadRequestException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The backup family of the format reset (docs/03 ADR-36 §18.4 doors 1 and 2; S4b-BL-206, kinds step 3) on the server:
 * the sample read as the family before is that family's sample byte for byte ({@code backup-sample-houses.json}) and
 * is written back unchanged, the update sample likewise, and what only the new family can break is a 400. The twins
 * are the website's {@code backup-family.spec.ts} and Android's {@code BackupFieldsTest}. docs/06 TC-U-203.
 */
class BackupWireTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode tree(String text) {
        return JSON.readTree(text);
    }

    @Test
    void theSampleReadAsTheFamilyBeforeIsThatFamilysSampleAndIsWrittenBackUnchanged() {
        var sample = CanonicalSample.text();
        assertThat(BackupWire.isCurrentFamily(tree(sample))).isTrue();
        assertThat(CanonicalSample.json()).isEqualTo(CanonicalSample.houses());
        assertThat(JSON.writeValueAsString(BackupWire.currentOf(tree(CanonicalSample.houses())))).isEqualTo(sample);
        // A file of the family before is read as it came.
        var before = tree(CanonicalSample.houses());
        assertThat(BackupWire.isCurrentFamily(before)).isFalse();
        assertThat(BackupWire.legacyOf(before)).isSameAs(before);
    }

    @Test
    void theUpdateSampleIsASlashThreeFileWithADeletionOfAHouse() throws Exception {
        var text = updateSample();
        var legacy = BackupWire.legacyOf(tree(text));
        assertThat(legacy.get("format").asString()).isEqualTo(BackupFormat.ID_WITH_DELETIONS);
        assertThat(legacy.get("deleted").get(0).get("kind").asString()).isEqualTo("house");
        assertThat(JSON.writeValueAsString(BackupWire.currentOf(legacy))).isEqualTo(text);
    }

    @Test
    void aNewerNumberOfTheNewFamilyReadsAsNewer() {
        var file = (ObjectNode) tree(CanonicalSample.text());
        file.put("format", "doorprints-backup/2");
        assertThat(BackupFormat.isNewer(BackupWire.legacyOf(file).get("format").asString())).isTrue();
    }

    @Test
    void whatOnlyTheNewFamilyCanBreakIsRefused() {
        assertBroken("\"kind\":\"house\",\"label\":\"x\"", "\"label\":\"x\"");
        assertBroken("\"money\":{\"amount\":1,\"cadence\":\"once\"}", "\"money\":{\"amount\":1.5,\"cadence\":\"once\"}");
        assertBroken("\"money\":{\"amount\":1,\"cadence\":\"once\"}", "\"money\":{}");
        assertBroken("\"extras\":{\"floor\":1}", "\"extras\":{\"deposit\":{\"amount\":5}}");
        assertBroken("\"extras\":{\"floor\":1}", "\"extras\":{\"myOffer\":{\"cadence\":\"month\"}}");
        assertBroken("\"extras\":{\"floor\":1}", "\"extras\":{\"from-newer\":1}");
        assertBroken("\"extras\":{\"floor\":1}", "\"extras\":{\"fromNewer\":[1]}");
        // Another kind may have another cadence, and an unknown extra of an extras type is read.
        var ok = one("\"kind\":\"school\",\"label\":\"x\",\"money\":{\"amount\":90000,\"cadence\":\"year\"},\"extras\":{\"fromNewerApp\":4,\"hasLift\":true}");
        var house = BackupWire.legacyOf(tree(ok)).get("houses").get(0);
        assertThat(house.get("price").asLong()).isEqualTo(90000);
        assertThat(house.has("priceType")).isFalse();
    }

    /** Well formed, but a cadence this reader does not support: a newer file ("update the app"), not a broken one. */
    @Test
    void anUnsupportedCadenceReadsAsANewerFile() {
        for (var candidate : new String[] {
                "\"kind\":\"house\",\"label\":\"x\",\"money\":{\"amount\":1,\"cadence\":\"year\"}",
                "\"kind\":\"house\",\"label\":\"x\",\"money\":{\"amount\":1,\"cadence\":\"fortnight\"}",
                "\"kind\":\"house\",\"label\":\"x\",\"extras\":{\"deposit\":{\"amount\":5,\"cadence\":\"month\"}}"}) {
            assertThat(BackupFormat.isNewer(BackupWire.legacyOf(tree(one(candidate))).get("format").asString())).as(candidate).isTrue();
        }
    }

    /** A price without a type: an amount and no cadence, read as a price with no type (owner, 2026-10-12). */
    @Test
    void aPriceWithoutATypeReadsAsNoType() {
        var house = BackupWire.legacyOf(tree(one("\"kind\":\"house\",\"label\":\"x\",\"money\":{\"amount\":12000000}"))).get("houses").get(0);
        assertThat(house.get("price").asLong()).isEqualTo(12_000_000L);
        assertThat(house.has("priceType")).isFalse();
        var legacy = (ObjectNode) tree(CanonicalSample.houses());
        ((ObjectNode) legacy.get("houses").get(0)).remove("priceType");
        var written = BackupWire.currentOf(legacy).get("candidates").get(0).get("money");
        assertThat(written.toString()).isEqualTo("{\"amount\":32000}");
    }

    /**
     * S4b-BL-248: a newer app's extras are kept through an import's rows ({@link BackupData}) and written back after the
     * known ones, by id: the candidates are the import vectors' {@code candidatesWrittenBack}, byte for byte, as on the
     * website ({@code backup-import.spec.ts}) and the phone ({@code ImportVectorsTest}).
     */
    @Test
    void aNewerAppsExtrasAreKeptThroughTheRowsAndWrittenBack() throws Exception {
        var kept = 0;
        for (var c : JSON.readTree(repoFile("docs/schemas/import-vectors.json")).get("checks")) {
            if (!c.has("candidatesWrittenBack")) continue;
            kept++;
            var data = JSON.treeToValue(BackupWire.legacyOf(c.get("data")), BackupData.class);
            var written = BackupWire.currentOf(JSON.valueToTree(data));
            assertThat(JSON.writeValueAsString(written.get("candidates")))
                    .as(c.get("name").asString()).isEqualTo(JSON.writeValueAsString(c.get("candidatesWrittenBack")));
        }
        assertThat(kept).isPositive();
        // Read, they are the house's extrasOther, in a candidate's order; more than 30 is refused, as on the apps.
        var house = BackupWire.legacyOf(tree(one("\"kind\":\"house\",\"label\":\"x\",\"extras\":{\"zLast\":\"a\",\"bedrooms\":1,\"fromNewerApp\":4}")))
                .get("houses").get(0);
        assertThat(house.get("extrasOther").toString()).isEqualTo("{\"zLast\":\"a\",\"fromNewerApp\":4}");
        assertThat(house.get("bedrooms").asInt()).isEqualTo(1);
    }

    private static String repoFile(String relative) throws Exception {
        var dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParent()) {
            var file = dir.resolve(relative);
            if (Files.isRegularFile(file)) return Files.readString(file);
        }
        throw new IllegalStateException(relative + " not found");
    }

    private static void assertBroken(String good, String bad) {
        var base = one("\"kind\":\"house\",\"label\":\"x\",\"money\":{\"amount\":1,\"cadence\":\"once\"},\"extras\":{\"floor\":1}");
        assertThat(BackupWire.legacyOf(tree(base)).get("houses").get(0).get("floor").asInt()).isEqualTo(1);
        var file = base.replace(good, bad);
        assertThat(file).as(bad).isNotEqualTo(base);
        assertThatThrownBy(() -> BackupWire.legacyOf(tree(file))).as(bad).isInstanceOf(BadRequestException.class);
    }

    private static String one(String candidate) {
        return "{\"format\":\"doorprints-backup/1\",\"exportedAt\":1,\"search\":{\"kind\":\"house\"},\"candidates\":[{\"id\":\"h1\","
                + candidate + ",\"lat\":0,\"lon\":0,\"status\":\"NEW\",\"checklist\":{}}],\"visits\":[],\"photos\":[]}";
    }

    private static String updateSample() throws Exception {
        var dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParent()) {
            var file = dir.resolve("docs/schemas/update-sample.json");
            if (Files.isRegularFile(file)) return Files.readString(file).strip();
        }
        throw new IllegalStateException("docs/schemas/update-sample.json not found");
    }
}
