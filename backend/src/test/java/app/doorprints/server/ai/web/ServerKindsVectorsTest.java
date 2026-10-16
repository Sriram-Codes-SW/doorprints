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

package app.doorprints.server.ai.web;

import app.doorprints.server.ai.HouseAiPins;
import app.doorprints.server.ai.kind.AiKinds;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server's half of {@code docs/schemas/kinds/space-scope-vectors.json} (kinds PR 28, S4b-BL-290; TC-U-277): a case that
 * says the server named the kinds {@code serverKinds} is a server whose {@code app.kinds.available} is that list, and the
 * kinds it names are the ones it accepts, the house always among them; a person's Ask and Plan in a space of another kind
 * is shown on the server's assistant only when the server names that kind, so a server that does not name it must refuse it.
 */
class ServerKindsVectorsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void theKindsAServerNamesAreTheKindsItAcceptsAndTheOthersAreRefused() throws Exception {
        var v = JSON.readTree(Files.readString(HouseAiPins.repoFile("docs/schemas/kinds/space-scope-vectors.json")));
        int checked = 0;
        for (var c : v.get("assistant")) {
            if (!c.has("serverKinds")) continue;
            var named = new ArrayList<String>();
            c.get("serverKinds").forEach(k -> named.add(k.asString()));
            var kinds = new AiKinds(named);
            var name = c.get("name").asString();
            assertThat(kinds.available()).as(name).isEqualTo(named);
            var kind = c.get("kind").asString();
            assertThat(kinds.isAvailable(kind)).as(name).isEqualTo(named.contains(kind));
            if (!kind.equals("house") && !c.get("ownKey").asBoolean() && c.get("shown").asBoolean()) {
                kinds.require(kind);
            }
            if (!kinds.isAvailable(kind)) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> kinds.require(kind))
                        .isInstanceOf(app.doorprints.server.common.BadRequestException.class);
            }
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(5);
        assertThat(List.of("house")).isEqualTo(new AiKinds(List.of()).available());
    }
}
