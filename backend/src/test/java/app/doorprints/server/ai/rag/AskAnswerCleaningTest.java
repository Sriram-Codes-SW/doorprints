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

package app.doorprints.server.ai.rag;

import app.doorprints.server.ai.rag.AskModels.ModelAnswer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S4b-BL-178: what Ask returns is the cleaned answer, and the citations are read from that text. */
class AskAnswerCleaningTest {

    private final UUID id = UUID.randomUUID();
    private final Document doc = Document.builder().id(id.toString())
            .text("House: Blue gate\nNotes: photos at https://example.com/l/1")
            .metadata(Map.of("label", "Blue gate")).build();

    @Test
    void aLinkOrImageTheModelAddedLosesItsAddressAndTheCitationStays() {
        var raw = new ModelAnswer("Quiet [house:" + id + "] ![x](https://evil.example/t.png?d=1) see https://evil.example/log"
                + " and https://example.com/l/1", List.of(id.toString()));
        var res = RagService.answered(raw, List.of(doc), "quiet");
        assertThat(res.answer()).isEqualTo("Quiet [house:" + id + "] x see [link removed] and https://example.com/l/1");
        assertThat(res.citations()).singleElement().satisfies(c -> assertThat(c.houseId()).isEqualTo(id));
        assertThat(res.grounded()).isTrue();
        assertThat(res.retrieved()).isEqualTo(1);
    }
}
