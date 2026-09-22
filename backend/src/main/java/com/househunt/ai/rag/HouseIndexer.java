package com.househunt.ai.rag;

import com.househunt.house.HouseChangedEvent;
import com.househunt.house.HouseDto;
import com.househunt.house.HouseRepository;
import com.househunt.visit.VisitDto;
import com.househunt.visit.VisitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Keeps the pgvector index in sync with houses. Changes are indexed asynchronously after the write transaction
 * commits, so a slow or failing embedding call never slows down or rolls back a save from the apps. If an update is
 * missed (provider down, quota exhausted), {@code POST /api/ai/reindex} rebuilds everything.
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

    public HouseIndexer(HouseRepository houses, VisitRepository visits, VectorStore vectorStore) {
        this.houses = houses;
        this.visits = visits;
        this.vectorStore = vectorStore;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onHouseChanged(HouseChangedEvent event) {
        try {
            index(event.houseId());
            asyncFailures.success();
        } catch (RuntimeException e) {
            asyncFailures.failure(event.houseId(), e);
        }
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
        vectorStore.add(List.of(HouseDocuments.toDocument(HouseDto.from(house), visitsOf(houseId))));
    }

    /**
     * Re-embeds every live house (in batches of {@value #BATCH}) and removes documents of deleted ones. A failing batch
     * does not stop the others; failures are logged as ONE WARN at the end (per-batch detail and the provider error
     * class at DEBUG), and {@link ReindexFailedException} reports how many houses were not indexed.
     *
     * @return the number of houses indexed
     */
    public int reindexAll() {
        var live = houses.findByDeletedFalseOrderByUpdatedAtDesc();
        int batches = (live.size() + BATCH - 1) / BATCH;
        int indexed = 0;
        int failedHouses = 0;
        int failedBatches = 0;
        for (int b = 0; b < batches; b++) {
            var slice = live.subList(b * BATCH, Math.min(live.size(), (b + 1) * BATCH));
            try {
                var docs = new ArrayList<Document>(slice.size());
                for (var house : slice) docs.add(HouseDocuments.toDocument(HouseDto.from(house), visitsOf(house.getId())));
                vectorStore.add(docs);
                indexed += slice.size();
            } catch (RuntimeException e) {
                failedBatches++;
                failedHouses += slice.size();
                log.debug("AI reindex batch {}/{} failed ({} house(s)): {}", b + 1, batches, slice.size(),
                        e.getClass().getName());
            }
        }
        var deletedIds = houses.findBySyncVersionGreaterThanOrderBySyncVersion(0).stream()
                .filter(h -> h.isDeleted())
                .map(h -> h.getId().toString())
                .toList();
        if (!deletedIds.isEmpty()) vectorStore.delete(deletedIds);
        if (failedBatches > 0) {
            var failed = new ReindexFailedException(indexed, failedHouses, failedBatches, batches);
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

        ReindexFailedException(int indexed, int failed, int failedBatches, int batches) {
            super("AI reindex incomplete: " + failed + " house(s) in " + failedBatches + " of " + batches
                    + " batch(es) not indexed, " + indexed + " indexed");
            this.indexed = indexed;
            this.failed = failed;
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
