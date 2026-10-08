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

import app.doorprints.server.ai.ProviderErrors;
import app.doorprints.server.ai.embedding.GeminiEmbeddingModel;
import app.doorprints.server.house.House;
import app.doorprints.server.house.HouseChangedEvent;
import app.doorprints.server.house.HouseRepository;
import app.doorprints.server.visit.VisitRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Batch failure handling and log summarising of {@link HouseIndexer}; no Spring, no database, no model. */
class HouseIndexerTest {

    private final HouseRepository houses = mock(HouseRepository.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final HouseIndexer indexer = new HouseIndexer(houses, visits, vectorStore);

    private static List<House> liveHouses(int n) {
        var list = new ArrayList<House>();
        for (int i = 0; i < n; i++) {
            var h = new House(UUID.randomUUID());
            h.setLabel("House " + i);
            list.add(h);
        }
        return list;
    }

    @Test
    void reindexKeepsGoingAfterAFailedBatchAndReportsIt() {
        when(houses.findByDeletedFalseOrderByUpdatedAtDesc()).thenReturn(liveHouses(HouseIndexer.BATCH + 5));
        when(houses.findDeletedIds()).thenReturn(List.of());
        doThrow(new IllegalStateException("provider down")).doNothing().when(vectorStore).add(anyList());

        assertThatThrownBy(indexer::reindexAll)
                .isInstanceOfSatisfying(HouseIndexer.ReindexFailedException.class, e -> {
                    assertThat(e.failed()).isEqualTo(HouseIndexer.BATCH);
                    assertThat(e.indexed()).isEqualTo(5);
                })
                .hasMessage("AI reindex incomplete: 20 house(s) in 1 of 2 batch(es) not indexed, 5 indexed")
                .hasMessageNotContaining("provider down");
        verify(vectorStore, times(2)).add(anyList());
    }

    @Test
    void reindexStopsAtTheFirstQuotaErrorInsteadOfBurningMoreCalls() {
        when(houses.findByDeletedFalseOrderByUpdatedAtDesc()).thenReturn(liveHouses(2 * HouseIndexer.BATCH + 5));
        when(houses.findDeletedIds()).thenReturn(List.of());
        var quota = new GeminiEmbeddingModel.GeminiEmbeddingException(
                "Vertex AI embedding request failed: HTTP 429 (RESOURCE_EXHAUSTED)", 429, "RESOURCE_EXHAUSTED");
        doNothing().doThrow(new RuntimeException("wrapped", quota)).when(vectorStore).add(anyList());

        assertThatThrownBy(indexer::reindexAll)
                .isInstanceOfSatisfying(HouseIndexer.ReindexFailedException.class, e -> {
                    assertThat(e.quotaExhausted()).isTrue();
                    assertThat(e.indexed()).isEqualTo(HouseIndexer.BATCH);
                    assertThat(e.failed()).isEqualTo(HouseIndexer.BATCH + 5);
                    assertThat(ProviderErrors.isQuotaExhausted(e)).isTrue();
                })
                .hasMessage("AI reindex incomplete: 25 house(s) in 2 of 3 batch(es) not indexed, 20 indexed "
                        + "(stopped: provider quota exhausted)");
        verify(vectorStore, times(2)).add(anyList()); // the third batch was never sent
    }

    @Test
    void indexOnChangeOffSkipsEmbeddingButStillRemovesDeletedHouses() {
        var off = new HouseIndexer(houses, visits, vectorStore, false);
        var live = liveHouses(1).getFirst();
        var gone = new House(UUID.randomUUID());
        gone.setLabel("gone");
        gone.setDeleted(true);
        when(houses.findById(live.getId())).thenReturn(Optional.of(live));
        when(houses.findById(gone.getId())).thenReturn(Optional.of(gone));

        off.onHouseChanged(new HouseChangedEvent(live.getId()));
        off.onHouseChanged(new HouseChangedEvent(gone.getId()));

        verify(vectorStore, never()).add(anyList());
        verify(vectorStore, never()).delete(List.of(live.getId().toString()));
        verify(vectorStore).delete(List.of(gone.getId().toString()));
    }

    /** Slice 3b-1: the document of a house carries its viewings (read once from the viewing records), not another's. */
    @Test
    void indexAndReindexCarryTheViewingsOfTheHouse() {
        var records = mock(app.doorprints.server.record.RecordRepository.class);
        var withViewings = new HouseIndexer(houses, visits, vectorStore, true, records,
                tools.jackson.databind.json.JsonMapper.builder().build());
        var mine = liveHouses(1).getFirst();
        var other = liveHouses(1).getFirst();
        var viewing = new app.doorprints.server.record.Record(new app.doorprints.server.record.RecordKey("viewing", "v_1"));
        viewing.setPayload("{\"houseId\":\"" + mine.getId() + "\",\"startsAt\":1790501400000,\"kind\":\"SECOND\","
                + "\"status\":\"PLANNED\",\"withWhom\":\"Ravi\",\"notes\":\"Bring a tape\"}");
        when(records.findByKeyTypeAndDeletedFalse("viewing")).thenReturn(List.of(viewing));
        when(records.findLiveViewingsOfHouse(mine.getId().toString())).thenReturn(List.of(viewing));
        when(houses.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(houses.findByDeletedFalseOrderByUpdatedAtDesc()).thenReturn(List.of(mine, other));
        when(houses.findDeletedIds()).thenReturn(List.of());

        withViewings.index(mine.getId());
        withViewings.reindexAll();

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).add(captor.capture());
        @SuppressWarnings("unchecked")
        var single = (List<org.springframework.ai.document.Document>) captor.getAllValues().get(0);
        @SuppressWarnings("unchecked")
        var batch = (List<org.springframework.ai.document.Document>) captor.getAllValues().get(1);
        assertThat(single.getFirst().getText())
                .contains("Viewing: 2026-09-27 09:30 | SECOND | PLANNED | Notes: Bring a tape").doesNotContain("Ravi");
        assertThat(batch.get(0).getText()).contains("Viewing: 2026-09-27");
        assertThat(batch.get(1).getText()).doesNotContain("Viewing:");
    }

    /** Slice 4a: the document of a house carries the area notes that reach it and its distances to the places. */
    @Test
    void indexAndReindexCarryTheAreaNotesAndDistancesOfTheHouse() {
        var records = mock(app.doorprints.server.record.RecordRepository.class);
        var withAreas = new HouseIndexer(houses, visits, vectorStore, true, records,
                tools.jackson.databind.json.JsonMapper.builder().build());
        var mine = liveHouses(1).getFirst();
        mine.setLat(13.0067);
        mine.setLon(80.2574);
        mine.setStreet("MG Road");
        var area = new app.doorprints.server.record.Record(new app.doorprints.server.record.RecordKey("area", "a_1"));
        area.setPayload("{\"name\":\"Adyar\",\"lat\":13.0067,\"lon\":80.2574,\"radiusM\":500}");
        var place = new app.doorprints.server.record.Record(new app.doorprints.server.record.RecordKey("place", "p_1"));
        place.setPayload("{\"name\":\"Office\",\"lat\":13.0827,\"lon\":80.2707}");
        var byArea = new app.doorprints.server.record.Record(new app.doorprints.server.record.RecordKey("areanote", "n_1"));
        byArea.setPayload("{\"areaId\":\"a_1\",\"text\":\"Floods in the monsoon\"}");
        var byStreet = new app.doorprints.server.record.Record(new app.doorprints.server.record.RecordKey("areanote", "n_2"));
        byStreet.setPayload("{\"street\":\"mg road\",\"text\":\"Noisy after 9 pm\"}");
        when(records.findByKeyTypeAndDeletedFalse("area")).thenReturn(List.of(area));
        when(records.findByKeyTypeAndDeletedFalse("place")).thenReturn(List.of(place));
        when(records.findByKeyTypeAndDeletedFalse("areanote")).thenReturn(List.of(byArea, byStreet));
        when(houses.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(houses.findByDeletedFalseOrderByUpdatedAtDesc()).thenReturn(List.of(mine));
        when(houses.findDeletedIds()).thenReturn(List.of());

        withAreas.index(mine.getId());
        withAreas.reindexAll();

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).add(captor.capture());
        for (var added : captor.getAllValues()) {
            @SuppressWarnings("unchecked")
            var docs = (List<org.springframework.ai.document.Document>) added;
            assertThat(docs.getFirst().getText()).contains("Area note: Floods in the monsoon",
                    "Area note: Noisy after 9 pm", "Distance to Office: 8.6 km");
        }
    }

    /**
     * A record repository over an in-memory list that honours the contract of the reads the indexer uses and counts
     * the rows each hands back (S4b-BL-162). The viewing filter looks at the payload on its own, as the database does.
     */
    private static final class CountingRecords {
        final app.doorprints.server.record.RecordRepository repo =
                mock(app.doorprints.server.record.RecordRepository.class);
        final List<app.doorprints.server.record.Record> rows = new ArrayList<>();
        final java.util.Map<String, Integer> rowsRead = new java.util.HashMap<>();
        final java.util.Map<String, Integer> calls = new java.util.HashMap<>();

        CountingRecords() {
            when(repo.findByKeyTypeAndDeletedFalse(anyString())).thenAnswer(inv -> {
                String type = inv.getArgument(0);
                var out = rows.stream().filter(r -> r.getKey().type().equals(type)).toList();
                count("type:" + type, out.size());
                return out;
            });
            when(repo.findLiveViewingsOfHouse(anyString())).thenAnswer(inv -> {
                String houseId = inv.getArgument(0);
                var out = rows.stream().filter(r -> r.getKey().type().equals("viewing")
                        && r.getPayload().contains("\"houseId\":\"" + houseId + "\"")).toList();
                count("viewing-of-house", out.size());
                return out;
            });
        }

        private void count(String what, int n) {
            calls.merge(what, 1, Integer::sum);
            rowsRead.merge(what, n, Integer::sum);
        }

        int totalRowsRead() {
            return rowsRead.values().stream().mapToInt(Integer::intValue).sum();
        }

        void add(String type, String id, String payload) {
            var r = new app.doorprints.server.record.Record(new app.doorprints.server.record.RecordKey(type, id));
            r.setPayload(payload);
            rows.add(r);
        }

        void viewing(String id, UUID house, String notes) {
            add("viewing", id, "{\"houseId\":\"" + house + "\",\"startsAt\":1790501400000,\"kind\":\"SECOND\","
                    + "\"status\":\"PLANNED\",\"notes\":\"" + notes + "\"}");
        }
    }

    private HouseIndexer indexerOver(CountingRecords records, VectorStore store) {
        return new HouseIndexer(houses, visits, store, true, records.repo,
                tools.jackson.databind.json.JsonMapper.builder().build());
    }

    private static String onlyDocumentText(VectorStore store) {
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(store).add(captor.capture());
        @SuppressWarnings("unchecked")
        var docs = (List<org.springframework.ai.document.Document>) captor.getValue();
        return docs.getFirst().getText();
    }

    /** S4b-BL-162: the rows read for one house save do not grow with the viewings of other houses. */
    @Test
    void savingOneHouseReadsNoViewingOfAnotherHouse() {
        var mine = liveHouses(1).getFirst();
        when(houses.findById(mine.getId())).thenReturn(Optional.of(mine));
        var few = new CountingRecords();
        few.viewing("v_mine", mine.getId(), "Bring a tape");
        few.viewing("v_one", UUID.randomUUID(), "x");
        indexerOver(few, vectorStore).index(mine.getId());

        var many = new CountingRecords();
        many.viewing("v_mine", mine.getId(), "Bring a tape");
        for (int i = 0; i < 1000; i++) many.viewing("v_o" + i, UUID.randomUUID(), "other " + i);
        indexerOver(many, mock(VectorStore.class)).index(mine.getId());

        assertThat(few.totalRowsRead()).isEqualTo(1);
        assertThat(many.totalRowsRead()).isEqualTo(1);
    }

    /** The filter keeps exactly the house's own viewings and never another's. */
    @Test
    void savingOneHouseCarriesItsOwnViewingsAndNotAnothers() {
        var records = new CountingRecords();
        var mine = liveHouses(1).getFirst();
        records.viewing("v_mine", mine.getId(), "Bring a tape");
        records.viewing("v_other", UUID.randomUUID(), "Secret of another house");
        when(houses.findById(mine.getId())).thenReturn(Optional.of(mine));

        indexerOver(records, vectorStore).index(mine.getId());

        assertThat(onlyDocumentText(vectorStore))
                .contains("Viewing: 2026-09-27 09:30 | SECOND | PLANNED | Notes: Bring a tape")
                .doesNotContain("Secret of another house");
    }

    /** The areas, places and area notes stay in the document of a single save. */
    @Test
    void savingOneHouseStillCarriesAreaNotesAndDistances() {
        var records = new CountingRecords();
        var mine = liveHouses(1).getFirst();
        mine.setLat(19.0760);
        mine.setLon(72.8777);
        mine.setStreet("Linking Road");
        records.add("area", "a_1", "{\"name\":\"Bandra\",\"lat\":19.0760,\"lon\":72.8777,\"radiusM\":500}");
        records.add("place", "p_1", "{\"name\":\"Office\",\"lat\":19.1136,\"lon\":72.8697}");
        records.add("areanote", "n_1", "{\"areaId\":\"a_1\",\"text\":\"Waterlogging in July\"}");
        records.add("areanote", "n_2", "{\"street\":\"linking road\",\"text\":\"Loud on weekends\"}");
        when(houses.findById(mine.getId())).thenReturn(Optional.of(mine));

        indexerOver(records, vectorStore).index(mine.getId());

        assertThat(onlyDocumentText(vectorStore)).contains("Area note: Waterlogging in July",
                "Area note: Loud on weekends", "Distance to Office: 4.3 km");
    }

    /**
     * Characterisation: the document of a house, written out by hand from the document rules (docs/11 slices 3b-1 and
     * 4a) with the contact number redacted, is the same from a single save and from a full re-index.
     */
    @Test
    void theDocumentTextOfASavedHouseIsExactlyAsBefore() {
        var records = new CountingRecords();
        var mine = liveHouses(1).getFirst();
        mine.setLat(28.6139);
        mine.setLon(77.2090);
        mine.setStreet("Barakhamba Road");
        records.viewing("v_2", mine.getId(), "Ask about parking");
        records.viewing("v_1", mine.getId(), "Water bill");
        records.add("place", "p_1", "{\"name\":\"Metro\",\"lat\":28.6304,\"lon\":77.2177}");
        records.add("areanote", "n_1", "{\"street\":\"BARAKHAMBA ROAD \",\"text\":\"Call 9876543210 about honking\"}");
        when(houses.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(houses.findByDeletedFalseOrderByUpdatedAtDesc()).thenReturn(List.of(mine));
        when(houses.findDeletedIds()).thenReturn(List.of());

        indexerOver(records, vectorStore).index(mine.getId());
        var single = onlyDocumentText(vectorStore);
        var reindexStore = mock(VectorStore.class);
        indexerOver(records, reindexStore).reindexAll();

        assertThat(single).isEqualTo(onlyDocumentText(reindexStore));
        assertThat(single).contains("Distance to Metro: 2.0 km", "Area note: Call")
                .contains("Viewing: 2026-09-27 09:30 | SECOND | PLANNED | Notes: Water bill\n"
                        + "Viewing: 2026-09-27 09:30 | SECOND | PLANNED | Notes: Ask about parking")
                .doesNotContain("9876543210");
    }

    /** A reindex of many houses reads each record type once, not once per house or per batch. */
    @Test
    void reindexOfManyHousesReadsEachRecordTypeOnce() {
        var records = new CountingRecords();
        var live = liveHouses(3 * HouseIndexer.BATCH + 1);
        for (var h : live) records.viewing("v_" + h.getId(), h.getId(), "n");
        records.add("area", "a_1", "{\"name\":\"X\",\"lat\":1.0,\"lon\":1.0}");
        records.add("place", "p_1", "{\"name\":\"Y\",\"lat\":1.0,\"lon\":1.0}");
        records.add("areanote", "n_1", "{\"street\":\"s\",\"text\":\"t\"}");
        when(houses.findByDeletedFalseOrderByUpdatedAtDesc()).thenReturn(live);
        when(houses.findDeletedIds()).thenReturn(List.of());

        indexerOver(records, vectorStore).reindexAll();

        assertThat(records.calls).containsOnly(java.util.Map.entry("type:viewing", 1),
                java.util.Map.entry("type:area", 1), java.util.Map.entry("type:place", 1),
                java.util.Map.entry("type:areanote", 1));
        assertThat(records.totalRowsRead()).isEqualTo(live.size() + 3);
    }

    @Test
    void reindexSucceedsAndRemovesDeletedHouses() {
        var gone = new House(UUID.randomUUID());
        gone.setLabel("gone");
        gone.setDeleted(true);
        when(houses.findByDeletedFalseOrderByUpdatedAtDesc()).thenReturn(liveHouses(3));
        when(houses.findDeletedIds()).thenReturn(List.of(gone.getId()));

        assertThat(indexer.reindexAll()).isEqualTo(3);
        verify(vectorStore, times(1)).add(anyList());
        verify(vectorStore).delete(List.of(gone.getId().toString()));
    }

    @Test
    void reindexReadsVisitsOncePerBatchNotOncePerHouse() {
        when(houses.findByDeletedFalseOrderByUpdatedAtDesc()).thenReturn(liveHouses(HouseIndexer.BATCH + 5));
        when(houses.findDeletedIds()).thenReturn(List.of());

        assertThat(indexer.reindexAll()).isEqualTo(HouseIndexer.BATCH + 5);
        verify(visits, times(2)).findByDeletedFalseAndHouseIdInOrderByArrivedAtDesc(anyList());
        verify(visits, never()).findByDeletedFalseAndHouseIdOrderByArrivedAtDesc(any());
        verify(houses, never()).findBySyncVersionGreaterThanOrderBySyncVersion(0);
    }

    @Test
    void emptyDatabaseIndexesNothing() {
        when(houses.findByDeletedFalseOrderByUpdatedAtDesc()).thenReturn(List.of());
        when(houses.findDeletedIds()).thenReturn(List.of());

        assertThat(indexer.reindexAll()).isZero();
        verify(vectorStore, never()).add(anyList());
    }

    private static final String WARN = "AI index update failed for {} house(s){}; they are searchable again after POST /api/ai/reindex";

    @Test
    void asyncFailuresAreSummarisedOncePerWindow() {
        var log = mock(Logger.class);
        var now = new AtomicLong(0);
        var summary = new HouseIndexer.FailureSummary(log, Duration.ofMinutes(5), now::get);
        var error = new IllegalStateException("provider down");

        summary.failure(UUID.randomUUID(), error); // first failure: reported at once
        for (int i = 0; i < 9; i++) summary.failure(UUID.randomUUID(), error); // same window: counted only
        verify(log, times(1)).warn(eq(WARN), eq(1), eq(""));
        assertThat(summary.pending()).isEqualTo(9);

        now.set(Duration.ofMinutes(5).toNanos() + 1);
        summary.failure(UUID.randomUUID(), error); // window over: one WARN for the 10 since the last report
        verify(log, times(1)).warn(eq(WARN), eq(10), eq(" since the last report"));
        assertThat(summary.pending()).isZero();
        verify(log, times(2)).warn(anyString(), any(Object.class), any(Object.class));
        // House ids and the provider error class at DEBUG only, never the exception message.
        verify(log, times(11)).debug(anyString(), any(Object.class), eq(IllegalStateException.class.getName()));
        verify(log, never()).warn(anyString(), any(Object.class), eq("provider down"));
    }

    @Test
    void intermittentFailuresBetweenSuccessesDoNotWarnPerHouse() {
        var log = mock(Logger.class);
        var now = new AtomicLong(0);
        var summary = new HouseIndexer.FailureSummary(log, Duration.ofMinutes(5), now::get);
        var error = new IllegalStateException("429");

        // Alternating 429s and successes (free-tier quota at its limit) for 4 minutes.
        for (int i = 0; i < 20; i++) {
            now.set(Duration.ofSeconds(i * 12L).toNanos());
            summary.failure(UUID.randomUUID(), error);
            summary.success();
        }
        verify(log, times(1)).warn(anyString(), any(Object.class), any(Object.class)); // only the first failure
        verify(log, times(1)).info(anyString(), any(Object.class)); // one "working again" after that WARN
        assertThat(summary.pending()).isEqualTo(19);

        now.set(Duration.ofMinutes(5).toNanos());
        summary.tick(); // window over, no new event needed: one summary for the rest
        verify(log, times(1)).warn(eq(WARN), eq(19), eq(" since the last report"));
        assertThat(summary.pending()).isZero();

        summary.tick(); // nothing pending: silent
        verify(log, times(2)).warn(anyString(), any(Object.class), any(Object.class));
    }

    @Test
    void tickWithinTheWindowWaitsAndAReindexDropsWhatIsPending() {
        var log = mock(Logger.class);
        var now = new AtomicLong(0);
        var summary = new HouseIndexer.FailureSummary(log, Duration.ofMinutes(5), now::get);
        var error = new IllegalStateException("provider down");

        summary.failure(UUID.randomUUID(), error);
        summary.failure(UUID.randomUUID(), error);
        now.set(Duration.ofMinutes(1).toNanos());
        summary.tick(); // still inside the window
        verify(log, times(1)).warn(anyString(), any(Object.class), any(Object.class));
        assertThat(summary.pending()).isEqualTo(1);

        summary.repairedByReindex(); // a full reindex re-embedded every house
        assertThat(summary.pending()).isZero();
        verify(log, times(1)).info(anyString(), eq(""));
        now.set(Duration.ofMinutes(10).toNanos());
        summary.tick();
        verify(log, times(1)).warn(anyString(), any(Object.class), any(Object.class));
    }

    @Test
    void successWithoutFailuresLogsNothing() {
        var log = mock(Logger.class);
        var summary = new HouseIndexer.FailureSummary(log, Duration.ofMinutes(5), () -> 0L);
        summary.success();
        summary.tick();
        verify(log, never()).warn(anyString(), any(Object.class), any(Object.class));
        verify(log, never()).info(anyString(), any(Object.class));
    }
}
