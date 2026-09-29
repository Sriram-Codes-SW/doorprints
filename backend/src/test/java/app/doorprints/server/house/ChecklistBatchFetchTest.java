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

package app.doorprints.server.house;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A house's checklist is an eager element collection. Loading many houses (the sync pull, the backup export, the AI
 * reindex) must not run one checklist select per house: {@code hibernate.default_batch_fetch_size} in application.yml
 * loads them in batches of 100.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // The API refuses to start without a key; this test never calls it.
        "app.api-key=it-batch-fetch-0123456789abcdef0123456789"})
@ResourceLock("database")
class ChecklistBatchFetchTest {

    @Autowired HouseRepository houses;
    @Autowired EntityManagerFactory emf;
    @Autowired TransactionTemplate tx;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        tx.executeWithoutResult(s -> houses.deleteAllById(created));
    }

    @Test
    void loadingManyHousesReadsTheirChecklistsInOneBatch() {
        var now = Instant.now();
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 25; i++) {
                var h = new House(UUID.randomUUID());
                h.setLabel("Batch " + i);
                h.setLat(12.97);
                h.setLon(77.64);
                h.setChecklist(Map.of("water", 5, "parking", i % 6));
                h.setCreatedAt(now);
                h.setUpdatedAt(now);
                houses.save(h);
                created.add(h.getId());
            }
        });
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        int scores = tx.execute(s -> houses.findByDeletedFalseOrderByUpdatedAtDesc().stream()
                .filter(h -> created.contains(h.getId()))
                .mapToInt(h -> h.getChecklist().get("water"))
                .sum());
        assertThat(scores).isEqualTo(25 * 5);
        // One select for the houses, one per 100 houses for their checklists (before: 1 + 25 here).
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }
}
