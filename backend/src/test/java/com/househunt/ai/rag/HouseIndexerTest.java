package com.househunt.ai.rag;

import com.househunt.house.House;
import com.househunt.house.HouseRepository;
import com.househunt.visit.VisitRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        when(houses.findBySyncVersionGreaterThanOrderBySyncVersion(0)).thenReturn(List.of());
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
    void reindexSucceedsAndRemovesDeletedHouses() {
        var gone = new House(UUID.randomUUID());
        gone.setLabel("gone");
        gone.setDeleted(true);
        when(houses.findByDeletedFalseOrderByUpdatedAtDesc()).thenReturn(liveHouses(3));
        when(houses.findBySyncVersionGreaterThanOrderBySyncVersion(0)).thenReturn(List.of(gone));

        assertThat(indexer.reindexAll()).isEqualTo(3);
        verify(vectorStore, times(1)).add(anyList());
        verify(vectorStore).delete(List.of(gone.getId().toString()));
    }

    @Test
    void emptyDatabaseIndexesNothing() {
        when(houses.findByDeletedFalseOrderByUpdatedAtDesc()).thenReturn(List.of());
        when(houses.findBySyncVersionGreaterThanOrderBySyncVersion(0)).thenReturn(List.of());

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
