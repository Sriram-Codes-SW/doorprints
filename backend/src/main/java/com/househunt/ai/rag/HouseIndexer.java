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
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keeps the pgvector index in sync with houses. Changes are indexed asynchronously after the write transaction
 * commits, so a slow or failing embedding call never slows down or rolls back a save from the apps. If an update is
 * missed (provider down, quota exhausted), {@code POST /api/ai/reindex} rebuilds everything.
 */
@Service
@ConditionalOnBooleanProperty("app.ai.enabled")
public class HouseIndexer {

    private static final Logger log = LoggerFactory.getLogger(HouseIndexer.class);
    private static final int BATCH = 20;

    private final HouseRepository houses;
    private final VisitRepository visits;
    private final VectorStore vectorStore;

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
        } catch (RuntimeException e) {
            // Id only: never log house content.
            log.warn("AI index update failed for house {}: {}", event.houseId(), e.getClass().getSimpleName());
        }
    }

    public void index(UUID houseId) {
        var house = houses.findById(houseId).orElse(null);
        if (house == null || house.isDeleted()) {
            vectorStore.delete(List.of(houseId.toString()));
            return;
        }
        vectorStore.add(List.of(HouseDocuments.toDocument(HouseDto.from(house), visitsOf(houseId))));
    }

    /** Re-embeds every live house and removes documents of deleted ones. Returns the number indexed. */
    public int reindexAll() {
        var live = houses.findByDeletedFalseOrderByUpdatedAtDesc();
        var batch = new ArrayList<Document>(BATCH);
        for (var house : live) {
            batch.add(HouseDocuments.toDocument(HouseDto.from(house), visitsOf(house.getId())));
            if (batch.size() == BATCH) {
                vectorStore.add(batch);
                batch = new ArrayList<>(BATCH);
            }
        }
        if (!batch.isEmpty()) vectorStore.add(batch);
        var deletedIds = houses.findBySyncVersionGreaterThanOrderBySyncVersion(0).stream()
                .filter(h -> h.isDeleted())
                .map(h -> h.getId().toString())
                .toList();
        if (!deletedIds.isEmpty()) vectorStore.delete(deletedIds);
        log.info("AI reindex done: {} houses indexed, {} deleted removed", live.size(), deletedIds.size());
        return live.size();
    }

    private List<VisitDto> visitsOf(UUID houseId) {
        return visits.findByDeletedFalseAndHouseIdOrderByArrivedAtDesc(houseId).stream().map(VisitDto::from).toList();
    }
}
