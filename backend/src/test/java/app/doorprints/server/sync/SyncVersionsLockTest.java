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

package app.doorprints.server.sync;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The writer lock of {@link SyncVersions} (docs/03 section 10.4, F-09; TC-I-14 is the concurrent-writes case, this is
 * its lock half): a transaction that takes versions holds the advisory lock until it ends, takes it from the database
 * once however many versions it takes (S4b-BL-159: the import takes 20 000), and the next transaction takes it again.
 *
 * <p>The seam is the public component against the real database. "Held" is asked of the database from a second
 * connection (a try-lock that fails while another transaction holds the lock), never read from the component's own
 * state. Runs against the shared PostGIS database like the other integration tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ResourceLock("database")
class SyncVersionsLockTest {

    @DynamicPropertySource
    static void apiKey(DynamicPropertyRegistry registry) {
        // The server will not start without a key; generated per run, never a literal in source; >= 32 chars.
        registry.add("app.api-key", () -> "sync-lock-it-" + UUID.randomUUID());
    }

    @Autowired SyncVersions versions;
    @Autowired TransactionTemplate tx;
    @Autowired DataSource dataSource;
    @Autowired EntityManagerFactory emf;

    /**
     * True when another connection cannot take the writer lock right now, so some transaction holds it. The
     * connection comes straight from the pool: a JdbcTemplate would join the test's own transaction and so be the
     * same session, which can always take its own lock.
     */
    private boolean lockIsHeld() {
        try (var other = dataSource.getConnection(); var statement = other.createStatement();
             var rows = statement.executeQuery("select pg_try_advisory_xact_lock(" + SyncVersions.LOCK_KEY + ")")) {
            // Autocommit: a successful try-lock is released at once, a failed one means it is taken elsewhere.
            rows.next();
            return !rows.getBoolean(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theLockIsHeldFromTheFirstVersionToTheEndOfTheTransaction() {
        assertThat(lockIsHeld()).as("nothing holds it before").isFalse();
        tx.executeWithoutResult(s -> {
            versions.next();
            assertThat(lockIsHeld()).as("held after the first version").isTrue();
            versions.next();
            assertThat(lockIsHeld()).as("still held after the second").isTrue();
        });
        assertThat(lockIsHeld()).as("released by the commit").isFalse();
    }

    @Test
    void theDatabaseIsAskedForTheLockOncePerTransactionAndAgainInTheNext() {
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        tx.executeWithoutResult(s -> {
            versions.lock();
            versions.next();
            versions.next();
            versions.next();
        });
        // One lock statement and three nextval statements.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);

        tx.executeWithoutResult(s -> {
            versions.next();
            assertThat(lockIsHeld()).as("a new transaction takes the lock again").isTrue();
        });
        assertThat(lockIsHeld()).isFalse();
    }

    @Test
    void aRolledBackTransactionReleasesTheLockAndTheNextOneTakesItAgain() {
        tx.executeWithoutResult(s -> {
            versions.next();
            s.setRollbackOnly();
        });
        assertThat(lockIsHeld()).isFalse();
        tx.executeWithoutResult(s -> {
            versions.next();
            assertThat(lockIsHeld()).isTrue();
        });
    }
}
