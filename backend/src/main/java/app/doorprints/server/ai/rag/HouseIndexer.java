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
import app.doorprints.server.ai.config.AiProperties;
import app.doorprints.server.house.HouseChangedEvent;
import app.doorprints.server.house.House;
import app.doorprints.server.house.HouseDto;
import app.doorprints.server.house.HouseRepository;
import app.doorprints.server.record.RecordRepository;
import app.doorprints.server.visit.Visit;
import app.doorprints.server.visit.VisitDto;
import app.doorprints.server.visit.VisitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Keeps the pgvector index in sync with houses. Changes are indexed asynchronously after the write transaction
 * commits, so a slow or failing embedding call never slows down or rolls back a save from the apps. If an update is
 * missed (provider down, quota exhausted), {@code POST /api/ai/reindex} rebuilds everything. With
 * {@code app.ai.index-on-change=false} ({@code AI_INDEX_ON_CHANGE}) saves are not embedded and only the full re-index
 * embeds (the eval harness uses this to halve its embedding calls); deletes still remove the house from the index.
 */
@Service
@ConditionalOnBooleanProperty("app.ai.enabled")
public class HouseIndexer {

    private static final Logger log = LoggerFactory.getLogger(HouseIndexer.class);
    static final int BATCH = 20;
    /** At most one WARN about failed async index updates per window. */
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(5);

    private final HouseRepository houses;
    private final VisitRepository visits;
    private final VectorStore vectorStore;
    private final FailureSummary asyncFailures = new FailureSummary(log, FAILURE_WINDOW, System::nanoTime);
    private final boolean indexOnChange;
    /** Where the viewings come from (records of type {@code viewing}); null in a unit test that has none. */
    private final RecordRepository records;
    private final ObjectMapper json;

    public HouseIndexer(HouseRepository houses, VisitRepository visits, VectorStore vectorStore) {
        this(houses, visits, vectorStore, true, null, null);
    }

    @Autowired
    public HouseIndexer(HouseRepository houses, VisitRepository visits, VectorStore vectorStore, AiProperties props,
                        RecordRepository records, ObjectMapper json) {
        this(houses, visits, vectorStore, props.indexOnChange(), records, json);
    }

    HouseIndexer(HouseRepository houses, VisitRepository visits, VectorStore vectorStore, boolean indexOnChange) {
        this(houses, visits, vectorStore, indexOnChange, null, null);
    }

    HouseIndexer(HouseRepository houses, VisitRepository visits, VectorStore vectorStore, boolean indexOnChange,
                 RecordRepository records, ObjectMapper json) {
        this.houses = houses;
        this.visits = visits;
        this.vectorStore = vectorStore;
        this.indexOnChange = indexOnChange;
        this.records = records;
        this.json = json;
    }

    /** The live viewings by house id: one query of the {@code viewing} type (at most 5 000 rows), not one per house. */
    private Map<String, List<ViewingLine>> viewingsByHouse() {
        if (records == null) return Map.of();
        return records.findByKeyTypeAndDeletedFalse("viewing").stream().map(r -> ViewingLine.from(r, json))
                .filter(Objects::nonNull).collect(Collectors.groupingBy(ViewingLine::houseId));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onHouseChanged(HouseChangedEvent event) {
        try {
            if (indexOnChange) {
                index(event.houseId());
            } else {
                // No embedding call, but a deleted house still leaves the index at once (AI-011).
                removeIfDeleted(event.houseId());
            }
            asyncFailures.success();
        } catch (RuntimeException e) {
            asyncFailures.failure(event.houseId(), e);
        }
    }

    private void removeIfDeleted(UUID houseId) {
        var house = houses.findById(houseId).orElse(null);
        if (house == null || house.isDeleted()) vectorStore.delete(List.of(houseId.toString()));
    }

    /** Reports failures still pending once their window is over, even if no further update comes in. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void reportPendingIndexFailures() {
        asyncFailures.tick();
    }

    public void index(UUID houseId) {
        var house = houses.findById(houseId).orElse(null);
        if (house == null || house.isDeleted()) {
            vectorStore.delete(List.of(houseId.toString()));
            return;
        }
        vectorStore.add(List.of(HouseDocuments.toDocument(HouseDto.from(house), visitsOf(houseId),
                viewingsByHouse().getOrDefault(houseId.toString(), List.of()))));
    }

    /**
     * Re-embeds every live house (in batches of {@value #BATCH}) and removes documents of deleted ones. A failing batch
     * does not stop the others, except when the provider reports its quota exhausted (HTTP 429 after the embedding
     * model's own retries): then the remaining batches are skipped instead of spending more calls on certain failures.
     * Failures are logged as ONE WARN at the end (per-batch detail and the provider error class at DEBUG), and
     * {@link ReindexFailedException} reports how many houses were not indexed and whether quota was the cause.
     *
     * @return the number of houses indexed
     */
    public int reindexAll() {
        var live = houses.findByDeletedFalseOrderByUpdatedAtDesc();
        int batches = (live.size() + BATCH - 1) / BATCH;
        // Read once for the whole run, not per batch.
        var viewingsByHouse = batches == 0 ? Map.<String, List<ViewingLine>>of() : viewingsByHouse();
        int indexed = 0;
        int failedHouses = 0;
        int failedBatches = 0;
        boolean quotaExhausted = false;
        for (int b = 0; b < batches; b++) {
            var slice = live.subList(b * BATCH, Math.min(live.size(), (b + 1) * BATCH));
            try {
                // One visits query per batch, not one per house; each house's visits stay newest first.
                var visitsByHouse = visits
                        .findByDeletedFalseAndHouseIdInOrderByArrivedAtDesc(slice.stream().map(House::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(Visit::getHouseId,
                                Collectors.mapping(VisitDto::from, Collectors.toList())));
                var docs = new ArrayList<Document>(slice.size());
                for (var house : slice) {
                    docs.add(HouseDocuments.toDocument(HouseDto.from(house),
                            visitsByHouse.getOrDefault(house.getId(), List.of()),
                            viewingsByHouse.getOrDefault(house.getId().toString(), List.of())));
                }
                vectorStore.add(docs);
                indexed += slice.size();
            } catch (RuntimeException e) {
                failedBatches++;
                failedHouses += slice.size();
                log.debug("AI reindex batch {}/{} failed ({} house(s)): {}", b + 1, batches, slice.size(),
                        e.getClass().getName());
                if (ProviderErrors.isQuotaExhausted(e)) {
                    quotaExhausted = true;
                    int skipped = live.size() - (b + 1) * BATCH;
                    if (skipped > 0) {
                        failedHouses += skipped;
                        failedBatches += batches - (b + 1);
                    }
                    break;
                }
            }
        }
        var deletedIds = houses.findDeletedIds().stream().map(UUID::toString).toList();
        if (!deletedIds.isEmpty()) vectorStore.delete(deletedIds);
        if (failedBatches > 0) {
            var failed = new ReindexFailedException(indexed, failedHouses, failedBatches, batches, quotaExhausted);
            log.warn(failed.getMessage());
            throw failed;
        }
        asyncFailures.repairedByReindex();
        log.info("AI reindex done: {} houses indexed, {} deleted removed", indexed, deletedIds.size());
        return indexed;
    }

    /** Some houses could not be embedded; the message carries counts only (no house content, no provider text). */
    public static class ReindexFailedException extends RuntimeException {
        private final int indexed;
        private final int failed;
        private final boolean quotaExhausted;

        ReindexFailedException(int indexed, int failed, int failedBatches, int batches) {
            this(indexed, failed, failedBatches, batches, false);
        }

        ReindexFailedException(int indexed, int failed, int failedBatches, int batches, boolean quotaExhausted) {
            super("AI reindex incomplete: " + failed + " house(s) in " + failedBatches + " of " + batches
                    + " batch(es) not indexed, " + indexed + " indexed"
                    + (quotaExhausted ? " (stopped: provider quota exhausted)" : ""));
            this.indexed = indexed;
            this.failed = failed;
            this.quotaExhausted = quotaExhausted;
        }

        /** The provider answered 429 / RESOURCE_EXHAUSTED and the remaining batches were skipped. */
        public boolean quotaExhausted() {
            return quotaExhausted;
        }

        public int indexed() {
            return indexed;
        }

        public int failed() {
            return failed;
        }
    }

    /**
     * Summarises failures of the per-house async updates so that an outage or intermittent 429s never log one WARN
     * per house: at most ONE WARN per window ({@link #FAILURE_WINDOW}). The first failure is reported at once; later
     * failures inside the window are only counted and reported in one summary WARN when the window is over (on the
     * next failure or the next {@link #tick()}, which runs every minute). The first success after a WARN logs one
     * INFO ("working again"); failures still pending then are reported by the next summary. House ids and the
     * provider error class are logged at DEBUG only. A successful full reindex repairs every earlier failure, so it
     * drops the pending count.
     */
    static final class FailureSummary {
        private final Logger logger;
        private final long windowNanos;
        private final LongSupplier clock;
        private int pending;
        private long lastWarn;
        private boolean everWarned;
        /** A WARN was logged and no success has been seen since. */
        private boolean outageOpen;

        FailureSummary(Logger logger, Duration window, LongSupplier clock) {
            this.logger = logger;
            this.windowNanos = window.toNanos();
            this.clock = clock;
        }

        synchronized void failure(UUID houseId, RuntimeException e) {
            pending++;
            logger.debug("AI index update failed for house {}: {}", houseId, e.getClass().getName());
            flushIfDue(clock.getAsLong());
        }

        synchronized void tick() {
            if (pending > 0) flushIfDue(clock.getAsLong());
        }

        synchronized void success() {
            if (outageOpen) {
                outageOpen = false;
                logger.info("AI index updates are working again{}", pending > 0
                        ? " (" + pending + " more house(s) failed meanwhile, reported in the next summary)" : "");
            }
        }

        synchronized void repairedByReindex() {
            pending = 0;
            success();
        }

        private void flushIfDue(long now) {
            if (everWarned && now - lastWarn < windowNanos) return;
            logger.warn("AI index update failed for {} house(s){}; they are searchable again after POST /api/ai/reindex",
                    pending, everWarned ? " since the last report" : "");
            pending = 0;
            lastWarn = now;
            everWarned = true;
            outageOpen = true;
        }

        synchronized int pending() {
            return pending;
        }
    }

    private List<VisitDto> visitsOf(UUID houseId) {
        return visits.findByDeletedFalseAndHouseIdOrderByArrivedAtDesc(houseId).stream().map(VisitDto::from).toList();
    }
}
