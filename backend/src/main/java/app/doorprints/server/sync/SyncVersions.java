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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out sync versions so that a client pulling {@code ?since=cursor} can never skip a change (threat model F-09).
 *
 * <p><b>Problem.</b> Versions come from {@code sync_seq}. Taken without coordination, transaction A can take 41,
 * transaction B take 42 and commit first; a client that pulls in between sees 42, stores cursor 42, and never sees 41
 * once A commits.
 *
 * <p><b>Fix.</b> Every writer first takes the transaction-scoped advisory lock {@link #LOCK_KEY}, then calls
 * {@code nextval}. The lock is held until commit or rollback, so versions become visible in exactly the order they
 * were assigned: while a writer holds version N uncommitted, nobody can take N+1. A reader either sees N (committed)
 * or nothing above N-1, and its next pull with {@code since=N-1} picks N up. Rolled-back versions leave harmless gaps.
 * Readers never take the lock. Writes to houses, visits and photos are serialised, which is fine for a single-user
 * app (each write is a few milliseconds); the lock also closes the read-check-write race in last-write-wins upserts,
 * because callers take it before reading the current row.
 */
@Component
public class SyncVersions {

    /** Arbitrary application-wide advisory lock id ("HH" + 42). */
    static final long LOCK_KEY = 0x4848_0000_002AL;

    @PersistenceContext
    private EntityManager em;

    /** Takes the writer lock (idempotent within one transaction). Call before reading a row you are about to update. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        em.createNativeQuery("select count(*) from pg_advisory_xact_lock(" + LOCK_KEY + ")").getSingleResult();
    }

    /**
     * The highest sync version handed out so far: {@code sync_seq}'s last value, or 0 on a new database whose sequence
     * has never been used. Clients compare it with their stored cursors to detect a server that was reset or restored
     * from an older dump (S4b-BL-20): their cursors come from committed rows, whose versions never exceed this, so a
     * cursor above it means the server lost changes. The sequence, not the rows' maximum, because rows can go (the
     * "delete all my data" call) while the sequence never goes back on a healthy server. One row read, no lock; a
     * version taken by a transaction still open may already count, which only makes the answer higher.
     */
    @Transactional(readOnly = true)
    public long highest() {
        return ((Number) em.createNativeQuery(
                "select case when is_called then last_value else 0 end from sync_seq").getSingleResult()).longValue();
    }

    /** Takes the writer lock (if not held yet) and returns the next sync version. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long next() {
        lock();
        return ((Number) em.createNativeQuery("select nextval('sync_seq')").getSingleResult()).longValue();
    }
}
