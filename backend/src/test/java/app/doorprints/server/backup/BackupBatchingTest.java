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

package app.doorprints.server.backup;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4b-BL-159: a big import is written in batches, not row by row. {@code POST /api/import} takes up to 20 000 rows;
 * before this change each row cost a select, a second select inside {@code save}, a {@code nextval}, and an
 * insert or update statement of its own, all in one persistence context that only grew.
 *
 * <p>The seam is the public endpoint. The count of prepared statements comes from Hibernate's statistics (switched on
 * for this context only); the bound is a ceiling with room over a measurement, so that an unbatched writer, which
 * needs several statements per row, fails it by a wide margin and an ordinary change does not.
 *
 * <p>Runs against the shared PostGIS database like {@code BackupApiTest}, which is why it holds the same lock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.properties.hibernate.generate_statistics=true",
                "app.rate-limit.requests-per-minute=60000",
                "app.rate-limit.burst=6000"})
@ResourceLock("database")
class BackupBatchingTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    /** Generated per run (never a literal in source, so secret scanners have nothing to flag); >= 32 chars. */
    private static final String KEY = "batching-it-" + UUID.randomUUID();

    /** Houses, and as many visits (one each), per file: well past the flush interval of 500 rows. */
    private static final int ROWS = 1500;

    /**
     * Ceiling on prepared statements for one import of {@code ROWS} houses and {@code ROWS} visits. Measured on the
     * day it was written: 3024 for the create and 3036 for the update, of which {@code 2 * ROWS} are the one
     * {@code nextval} each written row takes and the rest are the inserts, the updates and the reads, a few dozen in
     * all. Before the change: 18 009 for the create. The bound sits above the measurement so that a change to the
     * batch size does not fail it, and far below two statements per row, which an unbatched writer needs.
     */
    private static final long STATEMENT_BOUND = 3500;

    @DynamicPropertySource
    static void apiKey(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> KEY);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    EntityManagerFactory emf;

    RestClient api;

    @BeforeEach
    void setUp() {
        // The default client gives up after 10 s of silence; the unbatched import is slower than that, and the point
        // here is the statement count, not a timeout.
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMinutes(3));
        api = RestClient.builder().baseUrl("http://localhost:" + port).requestFactory(factory)
                .defaultHeader("X-API-Key", KEY).build();
        api.delete().uri("/api/data").header("X-Confirm-Delete", "DELETE-ALL-MY-DATA").retrieve().toBodilessEntity();
    }

    /**
     * Leaves the shared database empty: this class imports thousands of rows, and a later class that counts statements
     * over "every live house" (ChecklistBatchFetchTest) would otherwise load them all and fail depending on test order.
     */
    @AfterEach
    void wipe() {
        api.delete().uri("/api/data").header("X-Confirm-Delete", "DELETE-ALL-MY-DATA").retrieve().toBodilessEntity();
    }

    @Test
    void aLargeImportCreatesThenUpdatesInBatches() {
        var houseIds = IntStream.range(0, ROWS).mapToObj(i -> UUID.randomUUID()).toList();
        var t0 = Instant.now().minusSeconds(3600).toEpochMilli();

        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var created = postImport(file(houseIds, t0));
        long createStatements = statistics.getPrepareStatementCount();

        assertThat(count(created, "houses", "created")).isEqualTo(ROWS);
        assertThat(count(created, "visits", "created")).isEqualTo(ROWS);
        assertThat(createStatements).as("prepared statements for a create of %d houses and visits", ROWS)
                .isLessThan(STATEMENT_BOUND);

        // A preview of the same ids, newer, writes nothing and asks for no sync version: it only reads, chunk by chunk.
        statistics.clear();
        var preview = api.post().uri("/api/import?dryRun=true").contentType(MediaType.APPLICATION_JSON)
                .body(file(houseIds, t0 + 1000)).retrieve().body(MAP);
        assertThat(count(preview, "houses", "updated")).isEqualTo(ROWS);
        assertThat(count(preview, "visits", "updated")).isEqualTo(ROWS);
        assertThat(statistics.getEntityUpdateCount()).isZero();
        assertThat(statistics.getEntityInsertCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).as("a preview reads a chunk at a time").isLessThan(100);

        // The same ids again, newer: every row is an update of a stored one.
        statistics.clear();
        var updated = postImport(file(houseIds, t0 + 1000));
        long updateStatements = statistics.getPrepareStatementCount();

        assertThat(count(updated, "houses", "updated")).isEqualTo(ROWS);
        assertThat(count(updated, "visits", "updated")).isEqualTo(ROWS);
        assertThat(updateStatements).as("prepared statements for an update of %d houses and visits", ROWS)
                .isLessThan(STATEMENT_BOUND);

        // What was written is what the file said, in every batch (a clear must not lose or duplicate a row).
        var stats = api.get().uri("/api/stats").retrieve().body(MAP);
        assertThat(((Number) stats.get("houses")).intValue()).isEqualTo(ROWS);
        var export = api.get().uri("/api/export").retrieve().body(String.class);
        assertThat(export).contains("\"label\":\"House 0\"").contains("\"label\":\"House " + (ROWS - 1) + "\"")
                .contains("\"label\":\"House " + (ROWS / 2) + "\"");
        assertThat(export.split("\"street\":\"visit-", -1).length - 1).isEqualTo(ROWS);
    }

    private static String file(java.util.List<UUID> houseIds, long updatedAt) {
        var houses = IntStream.range(0, houseIds.size()).mapToObj(i -> "{\"id\":\"" + houseIds.get(i)
                + "\",\"label\":\"House " + i + "\",\"lat\":12.9,\"lon\":77.6,\"status\":\"NEW\","
                + "\"checklist\":{\"parking\":4,\"water\":5},\"createdAt\":" + (updatedAt - 1000)
                + ",\"updatedAt\":" + updatedAt + "}").collect(Collectors.joining(","));
        var visits = IntStream.range(0, houseIds.size()).mapToObj(i -> "{\"id\":\""
                + new UUID(0x7777L, i) + "\",\"houseId\":\"" + houseIds.get(i) + "\",\"lat\":12.9,\"lon\":77.6,"
                + "\"street\":\"visit-" + i + "\",\"arrivedAt\":" + (updatedAt - 500) + ",\"source\":\"MANUAL\","
                + "\"updatedAt\":" + updatedAt + "}").collect(Collectors.joining(","));
        return "{\"format\":\"" + BackupFormat.ID + "\",\"exportedAt\":" + Instant.now().toEpochMilli()
                + ",\"houses\":[" + houses + "],\"visits\":[" + visits + "],\"photos\":[]}";
    }

    private Map<String, Object> postImport(String body) {
        return api.post().uri("/api/import").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(MAP);
    }

    @SuppressWarnings("unchecked")
    private static int count(Map<String, Object> report, String kind, String outcome) {
        return ((Number) ((Map<String, Object>) report.get(kind)).get(outcome)).intValue();
    }
}
