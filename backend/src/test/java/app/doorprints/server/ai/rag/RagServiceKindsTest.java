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

import app.doorprints.server.ai.config.AiProperties;
import app.doorprints.server.ai.kind.AiKinds;
import app.doorprints.server.ai.rag.AskModels.AskFilters;
import app.doorprints.server.ai.web.AiUnavailableException;
import app.doorprints.server.house.House;
import app.doorprints.server.house.HouseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S4b-BL-308 (T-I49): a document stored under one {@code KINDS_AVAILABLE} is not read under another. The stored
 * documents carry the kind they were written under; Ask drops one whose kind has left the list, without a reindex, and
 * what is kept is still scrubbed of the contact's name and phone on read.
 */
class RagServiceKindsTest {

    private static Document doc(String kind, String text) {
        var meta = new java.util.HashMap<String, Object>();
        meta.put("houseId", UUID.randomUUID().toString());
        if (kind != null) meta.put("kind", kind);
        return Document.builder().id(UUID.randomUUID().toString()).text(text).metadata(meta).score(0.9).build();
    }

    @Test
    void aDocumentOfAKindThatLeftTheListIsDroppedAndHousesAndOlderDocumentsStay() {
        var house = doc(null, "House: A");
        var oldStyle = doc(null, "House: B");
        var school = doc("school", "School: C");
        var docs = List.of(house, school, oldStyle);

        assertThat(RagService.availableOnly(docs, AiKinds.OFF)).containsExactly(house, oldStyle);
        assertThat(RagService.availableOnly(docs, AiKinds.schools(true))).containsExactly(house, school, oldStyle);
        assertThat(RagService.availableOnly(null, AiKinds.OFF)).isNull();
    }

    @Test
    void askUnderTheHouseAloneNeverSendsAStoredSchoolToTheModelButSendsItWhenTheListHasIt() {
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(doc("school", "School: Pine Hill, fees Rs 96000")));
        var chat = mock(ChatClient.class);
        when(chat.prompt()).thenThrow(new IllegalStateException("model down"));
        var props = new AiProperties(true, null, null, null, null, null, null, new AiProperties.Rag(20, null), null, null, null);
        var none = new AskFilters(null, null, null, null, null);

        var off = new RagService(chat, store, props, mock(HouseRepository.class), AiKinds.OFF);
        assertThat(off.ask("Which school has the lowest fees?", none).answer()).isEqualTo(AskPrompts.I_DONT_KNOW);

        var on = new RagService(chat, store, props, mock(HouseRepository.class), AiKinds.schools(true));
        assertThatThrownBy(() -> on.ask("Which school has the lowest fees?", none))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void whatIsKeptIsStillScrubbedOfTheContactOnRead() {
        var house = new House(UUID.randomUUID());
        house.setContactName("Ramesh Kumar");
        house.setContactPhone("98765 43210");
        var school = doc("school", "School: Pine Hill. Call Ramesh Kumar on 98765 43210.");
        var kept = RagService.availableOnly(List.of(school), AiKinds.schools(true));
        var out = RagService.redacted(kept, Map.of(school.getId(), house));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getText()).doesNotContain("Ramesh").doesNotContain("98765");
    }

    /** S4b-BL-269: the system text and the user text the model gets are the school's when every retrieved document is a school's. */
    @Test
    void askSendsASchoolsPromptForSchoolDocumentsAndTheHousesWhenAHouseIsAmongThem() {
        var props = new AiProperties(true, null, null, null, null, null, null, new AiProperties.Rag(20, null), null, null, null);
        var none = new AskFilters(null, null, null, null, null);
        var school = doc("school", "School: Pine Hill, fees Rs 96000");
        var house = doc(null, "House: A");
        for (var withHouse : List.of(false, true)) {
            var store = mock(VectorStore.class);
            when(store.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                    .thenReturn(withHouse ? List.of(school, house) : List.of(school));
            var spec = mock(ChatClient.ChatClientRequestSpec.class, org.mockito.Mockito.RETURNS_SELF);
            when(spec.call()).thenThrow(new IllegalStateException("model down"));
            var chat = mock(ChatClient.class);
            when(chat.prompt()).thenReturn(spec);
            var service = new RagService(chat, store, props, mock(HouseRepository.class), AiKinds.schools(true));
            assertThatThrownBy(() -> service.ask("Which school has the lowest fees?", none)).isInstanceOf(AiUnavailableException.class);
            var system = org.mockito.ArgumentCaptor.forClass(String.class);
            var user = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(spec).system(system.capture());
            org.mockito.Mockito.verify(spec).user(user.capture());
            if (withHouse) {
                assertThat(system.getValue()).contains("ONE person's house hunt").doesNotContain("school search");
                assertThat(user.getValue()).startsWith("<houses-");
            } else {
                assertThat(system.getValue()).contains("ONE person's school search").contains(AiKinds.SCHOOL.refusal());
                assertThat(user.getValue()).startsWith("<schools-").contains("School: Pine Hill, fees Rs 96000");
            }
        }
    }
}
