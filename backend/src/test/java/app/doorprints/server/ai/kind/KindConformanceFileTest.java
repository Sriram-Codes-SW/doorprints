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

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The synthetic kind of the conformance suite, {@code docs/schemas/kinds/test-all.json} (kinds PR 32, docs/03 section 18.26): the loader accepts it,
 * every capability is on, the server can serve it with no Java of its own, and no build lists it.
 */
class KindConformanceFileTest {

    private static KindFile zzAll() throws Exception {
        return KindFiles.parse(Files.readString(HouseAiPins.repoFile("docs/schemas/kinds/test-all.json")));
    }

    @Test
    void theLoaderAcceptsTheKindWithEveryCapabilityOn() throws Exception {
        var k = zzAll();
        assertThat(k.id()).isEqualTo("zz_all");
        var c = k.capabilities();
        assertThat(c.map()).isTrue();
        assertThat(c.hunt()).isNotNull();
        assertThat(c.rooms()).isTrue();
        assertThat(c.moveIn()).isTrue();
        assertThat(c.contacts()).isNotNull();
        assertThat(c.viewings()).isNotNull();
        assertThat(c.compare()).isNotNull();
        assertThat(c.paths()).isTrue();
        assertThat(c.identity()).isNotNull();
        assertThat(c.baseline()).isFalse();
        assertThat(c.filters()).isNotEmpty();
        assertThat(k.moneyCadences()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void theServerServesItWithNoJavaAndOnlyWhenTheConfigurationListsIt() throws Exception {
        var k = AiKind.from(zzAll(), null);
        assertThat(k).as("a kind with no prompt pack is written from its words").isNotNull();
        assertThat(k.id()).isEqualTo("zz_all");
        assertThat(new AiKinds(List.of("house", "school")).available()).doesNotContain("zz_all");
        assertThat(new AiKinds(List.of()).isAvailable("zz_all")).isFalse();
        assertThat(AiKinds.OFF.available()).containsExactly("house");
    }

    @Test
    void noBuildShipsItAndTheDefaultConfigurationDoesNotListIt() throws Exception {
        assertThat(KindFiles.builtIn().keySet()).doesNotContain("zz_all");
        try (var dir = Files.list(HouseAiPins.repoFile("backend/src/main/resources/kinds"))) {
            assertThat(dir.map(p -> p.getFileName().toString()).toList()).doesNotContain("zz_all.json", "test-all.json");
        }
        assertThat(Files.readString(HouseAiPins.repoFile("backend/src/main/resources/application.yml"))).doesNotContain("zz_all");
    }
}
