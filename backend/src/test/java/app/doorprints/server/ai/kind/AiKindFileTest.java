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
package app.doorprints.server.ai.kind;

import app.doorprints.server.ai.HouseAiPins;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server's copy of the schools kind's AI slots ({@link AiKinds#SCHOOL}) against {@code docs/schemas/kinds/school.json}
 * and the English words of {@code kind-ai-vectors.json} (kinds PR 7, S4b-BL-211): the four slots word for word, each
 * extra a slot names with the file's type, range and cadence, and the rules the readers rely on: {@code never} is in
 * no other slot, every {@code ask} id is one a document can write, the extract fills only the types its sanitizer
 * reads, and the flag off (or a kind that is not built in) reads every candidate as a house.
 */
class AiKindFileTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> SECTIONS = Set.of("areaNotes", "distances", "visits");
    private static final Set<String> CORE = Set.of("label", "address", "street", "locality", "status", "rating", "notes");

    private static JsonNode file() throws Exception {
        return JSON.readTree(Files.readString(HouseAiPins.repoFile("docs/schemas/kinds/school.json")));
    }

    private static List<String> list(JsonNode a) {
        var out = new ArrayList<String>();
        a.forEach(n -> out.add(n.asString()));
        return out;
    }

    @Test
    void theSlotsAreTheKindFilesWordForWord() throws Exception {
        var ai = file().get("ai");
        var k = AiKinds.SCHOOL;
        assertThat(k.extract()).isEqualTo(list(ai.get("extract")));
        assertThat(k.ask()).isEqualTo(list(ai.get("ask")));
        assertThat(k.plan()).isEqualTo(list(ai.get("plan")));
        assertThat(k.never()).isEqualTo(list(ai.get("never")));
    }

    @Test
    void everyExtraASlotNamesHasTheFilesTypeRangeAndCadence() throws Exception {
        var k = AiKinds.SCHOOL;
        var named = new HashSet<String>();
        named.addAll(k.extract());
        named.addAll(k.ask());
        named.addAll(k.plan());
        var ids = new ArrayList<String>();
        for (var e : file().get("extras")) {
            if (!named.contains(e.get("id").asString())) continue;
            ids.add(e.get("id").asString());
            var copy = k.extra(e.get("id").asString());
            assertThat(copy).as(e.get("id").asString()).isNotNull();
            assertThat(copy.type()).isEqualTo(e.get("type").asString());
            if (e.has("maxLength")) assertThat(copy.maxLength()).isEqualTo(e.get("maxLength").asInt());
            if (e.has("min")) assertThat(copy.min()).isEqualTo(e.get("min").asLong());
            if (e.has("max")) assertThat(copy.max()).isEqualTo(e.get("max").asLong());
            if (e.has("cadences")) assertThat(List.of(copy.cadence())).isEqualTo(list(e.get("cadences")));
            if (e.has("options")) {
                var options = new ArrayList<String>();
                e.get("options").forEach(o -> options.add(o.get("id").asString()));
                assertThat(new ArrayList<>(copy.options().keySet())).isEqualTo(options);
            }
        }
        assertThat(k.extras().stream().map(AiKind.Extra::id).toList()).isEqualTo(ids);
    }

    @Test
    void theWordsAreTheVectorsAndTheReadersRulesHold() {
        var school = HouseAiPins.kindAiVectors().get("kinds").get(0);
        var k = AiKinds.SCHOOL;
        assertThat(k.noun()).isEqualTo(school.get("noun").asString());
        for (var e : school.get("extras")) {
            var copy = k.extra(e.get("id").asString());
            assertThat(copy.words()).isEqualTo(e.get("words").asString());
            if (e.has("options")) {
                e.get("options").properties().forEach(o -> assertThat(copy.options().get(o.getKey()))
                        .isEqualTo(o.getValue().asString()));
            }
        }
        for (var id : k.never()) assertThat(k.ask()).doesNotContain(id);
        for (var id : k.never()) assertThat(k.plan()).doesNotContain(id);
        for (var id : k.ask()) {
            assertThat(CORE.contains(id) || SECTIONS.contains(id) || k.extra(id) != null).as(id).isTrue();
        }
        for (var e : k.extras()) {
            if (k.extract().contains(e.id())) assertThat(e.type()).isIn("enum", "bool", "text", "money");
        }
    }

    @Test
    void withTheFlagOffOrAnUnknownKindEveryCandidateIsAHouse() {
        assertThat(AiKinds.OFF.of("school")).isNull();
        assertThat(AiKinds.schools(false).of("school")).isNull();
        assertThat(AiKinds.schools(true).of("school")).isSameAs(AiKinds.SCHOOL);
        assertThat(AiKinds.schools(true).of("house")).isNull();
        assertThat(AiKinds.schools(true).of("clinic")).isNull();
        assertThat(AiKinds.schools(true).of(null)).isNull();
    }

    /** {@code app.kinds.available} (S4b-BL-280): the house is always in it; a kind outside it is not available, a 400. */
    @Test
    void theAvailableKindsAreAListWithTheHouseAlwaysIn() {
        assertThat(AiKinds.OFF.available()).containsExactly("house");
        assertThat(new AiKinds(null).available()).containsExactly("house");
        assertThat(new AiKinds(List.of(" school ", "house", "school", "", "Not A Kind")).available())
                .containsExactly("house", "school");
        var houseOnly = new AiKinds(List.of("house"));
        assertThat(houseOnly.isAvailable(null)).isTrue();
        assertThat(houseOnly.isAvailable("house")).isTrue();
        assertThat(houseOnly.isAvailable("school")).isFalse();
        houseOnly.require("house");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> houseOnly.require("school"))
                .isInstanceOf(app.doorprints.server.common.BadRequestException.class)
                .hasMessage("kind school is not available");
        // A kind that is listed but has no built-in file is available and read as a house.
        var clinic = new AiKinds(List.of("house", "clinic"));
        assertThat(clinic.isAvailable("clinic")).isTrue();
        assertThat(clinic.of("clinic")).isNull();
        assertThat(clinic.of("school")).isNull();
    }

    /** S4b-BL-270: a Plan record carries an extra only when the file's {@code plan} slot names it, and the details tool (the {@code ask} slot) then does too. */
    @Test
    void everyExtraThePlanSlotNamesIsAKnownExtraTheAskSlotNamesToo() throws Exception {
        var k = AiKinds.SCHOOL;
        var planExtras = k.plan().stream().filter(id -> k.extra(id) != null).toList();
        assertThat(planExtras).containsExactly("board", "medium", "grades", "commuteMinutes", "busService", "fees");
        assertThat(k.ask()).containsAll(planExtras);
        assertThat(k.plan()).doesNotContainAnyElementsOf(k.never()).doesNotContain("admissionFee");
        // Every id in the slot is a core field a view keeps or an extra of the kind: nothing the Plan view would drop silently.
        assertThat(k.plan()).allMatch(id -> k.extra(id) != null || CORE.contains(id));
    }
}
