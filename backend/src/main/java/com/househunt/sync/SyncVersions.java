package com.househunt.sync;

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

    /** Takes the writer lock (if not held yet) and returns the next sync version. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long next() {
        lock();
        return ((Number) em.createNativeQuery("select nextval('sync_seq')").getSingleResult()).longValue();
    }
}
