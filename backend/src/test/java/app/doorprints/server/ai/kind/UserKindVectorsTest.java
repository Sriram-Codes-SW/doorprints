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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The kinds a person adds (docs/03 ADR-38, docs/06 TC-U-292): the server stores no such kind and serves none, but they are the
 * same format as its built-in files, so its one reader ({@link KindFiles#parse}) is held to the shared vectors
 * ({@code docs/schemas/kinds/user-kind-vectors.json}): it takes every valid sample, refuses exactly the invalid cases whose
 * {@code base} is true (the rules of the built-in format: size, duplicate, prototype and map keys, ids, types, ranges,
 * capabilities), and takes the ones only the stricter user profile refuses (the apps' readers). No built-in file has a
 * person's id prefix, so a person's kind can never be one of the server's.
 */
class UserKindVectorsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode vectors() throws Exception {
        return JSON.readTree(Files.readString(HouseAiPins.repoFile("docs/schemas/kinds/user-kind-vectors.json")));
    }

    private static ObjectNode sample(JsonNode v, String id) {
        for (var s : v.get("valid")) if (s.get("file").get("id").asString().equals(id)) return (ObjectNode) s.get("file").deepCopy();
        throw new IllegalArgumentException(id);
    }

    private static ObjectNode label(String text) {
        var o = JSON.createObjectNode();
        o.put("text", text);
        return o;
    }

    /** The text of a case, as the vectors' note defines `json`, `generate` and `patch`. */
    static String text(JsonNode v, JsonNode c) {
        if (c.has("json")) return c.get("json").asString();
        if (c.has("generate")) {
            var g = c.get("generate");
            return g.get("prefix").asString() + g.get("repeat").asString().repeat(g.get("times").asInt()) + g.get("suffix").asString();
        }
        var p = c.get("patch");
        var file = sample(v, p.get("base").asString());
        if (p.has("set")) {
            for (var op : p.get("set")) {
                var path = op.get(0);
                var parent = walk(file, path);
                var last = path.get(path.size() - 1);
                if (parent instanceof ObjectNode o) o.set(last.asString(), op.get(1).deepCopy());
                else ((ArrayNode) parent).set(last.asInt(), op.get(1).deepCopy());
            }
        }
        if (p.has("remove")) {
            for (var path : p.get("remove")) {
                var parent = walk(file, path);
                var last = path.get(path.size() - 1);
                if (parent instanceof ObjectNode o) o.remove(last.asString());
                else ((ArrayNode) parent).remove(last.asInt());
            }
        }
        if (p.has("manyExtras")) {
            var list = JSON.createArrayNode();
            for (int i = 1; i <= p.get("manyExtras").asInt(); i++) {
                var e = list.addObject();
                e.put("id", "x" + i).put("type", "text").put("maxLength", 10).put("section", "details").put("order", i);
                e.set("label", label("Field " + i));
            }
            file.set("extras", list);
        }
        if (p.has("manyOptions")) {
            var m = p.get("manyOptions");
            var options = JSON.createArrayNode();
            for (int i = 1; i <= m.get("count").asInt(); i++) options.addObject().put("id", "O" + i).set("label", label("Option " + i));
            ((ObjectNode) file.get("extras").get(m.get("extra").asInt())).set("options", options);
        }
        if (p.has("manyEnums")) {
            var m = p.get("manyEnums");
            var list = JSON.createArrayNode();
            for (int i = 1; i <= m.get("count").asInt(); i++) {
                var e = list.addObject();
                e.put("id", "e" + i).put("type", "enum").put("section", "details").put("order", i);
                e.set("label", label("Field " + i));
                var options = e.putArray("options");
                for (int j = 1; j <= m.get("options").asInt(); j++) options.addObject().put("id", "O" + j).set("label", label("Option " + j));
            }
            file.set("extras", list);
        }
        if (p.has("manyChecks")) {
            var list = JSON.createArrayNode();
            for (int i = 1; i <= p.get("manyChecks").asInt(); i++) list.addObject().put("key", "c" + i).set("label", label("Check " + i));
            file.set("checklist", list);
        }
        return JSON.writeValueAsString(file);
    }

    private static JsonNode walk(JsonNode root, JsonNode path) {
        JsonNode n = root;
        for (int i = 0; i < path.size() - 1; i++) {
            var k = path.get(i);
            n = k.isNumber() ? n.get(k.asInt()) : n.get(k.asString());
        }
        return n;
    }

    @Test
    void theValidSamplesAreReadByTheBuiltInReaderToo() throws Exception {
        var v = vectors();
        assertThat(v.get("valid").size()).isGreaterThanOrEqualTo(4);
        for (var s : v.get("valid")) {
            var json = JSON.writeValueAsString(s.get("file"));
            var file = KindFiles.parse(json);
            assertThat(file.id()).as(s.get("name").asString()).isEqualTo(s.get("resolved").get("id").asString());
            assertThat(file.ai().extract()).isEmpty();
        }
    }

    @Test
    void theBuiltInReaderRefusesExactlyTheCasesOfTheBuiltInFormat() throws Exception {
        var v = vectors();
        var refused = new ArrayList<String>();
        var taken = new ArrayList<String>();
        for (var c : v.get("invalid")) {
            var name = c.get("name").asString();
            var json = text(v, c);
            if (c.get("base").asBoolean()) {
                assertThatThrownBy(() -> KindFiles.parse(json)).as(name).isInstanceOf(KindFiles.KindFileException.class);
                refused.add(name);
            } else {
                assertThatCode(() -> KindFiles.parse(json)).as(name).doesNotThrowAnyException();
                taken.add(name);
            }
        }
        assertThat(refused).hasSizeGreaterThan(40);
        assertThat(taken).hasSizeGreaterThan(30);
    }

    @Test
    void noBuiltInKindHasAPersonsPrefixAndTheMapKeysAreTheSame() throws Exception {
        var v = vectors();
        for (var id : KindFiles.builtIn().keySet()) assertThat(id).doesNotMatch(v.get("limits").get("idPattern").asString());
        for (var r : v.get("reserved")) assertThat(r.asString()).doesNotMatch(v.get("limits").get("idPattern").asString());
        var caps = JSON.readTree(Files.readString(HouseAiPins.repoFile("docs/schemas/kinds/capabilities-vectors.json")));
        var keys = new ArrayList<String>();
        caps.get("forbiddenMapKeys").forEach(k -> keys.add(k.asString()));
        assertThat(KindFiles.FORBIDDEN_MAP_KEYS).containsExactlyInAnyOrderElementsOf(List.copyOf(keys));
    }
}
