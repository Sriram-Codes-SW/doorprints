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

package app.doorprints.server.ai.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Minimal in-memory token bucket (no Bucket4j): each key gets {@code capacity} tokens that refill continuously at
 * {@code refillPerMinute}. One instance per JVM is plenty for a single-user app on one small host; if the API is
 * ever scaled out, move this to Postgres or Redis.
 */
public class TokenBucketRateLimiter {

    /**
     * The verdict for one request; when refused, the whole seconds to wait for a token (at least 1).
     */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private static final int MAX_KEYS = 10_000;
    private static final double NANOS_PER_MINUTE = 60_000_000_000.0;

    private final int capacity;
    private final double refillPerMinute;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int capacity, int refillPerMinute) {
        this(capacity, refillPerMinute, System::nanoTime);
    }

    TokenBucketRateLimiter(int capacity, int refillPerMinute, LongSupplier nanoClock) {
        if (capacity <= 0 || refillPerMinute <= 0) throw new IllegalArgumentException("capacity and rate must be > 0");
        this.capacity = capacity;
        this.refillPerMinute = refillPerMinute;
        this.nanoClock = nanoClock;
    }

    /**
     * Takes one token from the key's bucket, refilling it for the time since the last call. A new key starts with a
     * full bucket. Past 10,000 distinct keys all buckets are dropped, which bounds memory against key spraying at the
     * price of forgetting limits.
     * Thread-safe: each bucket is locked while it is updated.
     */
    public Decision tryAcquire(String key) {
        if (buckets.size() > MAX_KEYS) buckets.clear(); // crude memory guard against key spraying
        var bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, nanoClock.getAsLong()));
        synchronized (bucket) {
            long now = nanoClock.getAsLong();
            bucket.tokens = Math.min(capacity, bucket.tokens + (now - bucket.lastRefill) * refillPerMinute / NANOS_PER_MINUTE);
            bucket.lastRefill = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return new Decision(true, 0);
            }
            double missingSeconds = (1.0 - bucket.tokens) * 60.0 / refillPerMinute;
            return new Decision(false, Math.max(1, (long) Math.ceil(missingSeconds - 1e-9)));
        }
    }

    /**
     * Mutable state of one key: the (fractional) tokens left and when they were last topped up.
     */
    private static final class Bucket {
        double tokens;
        long lastRefill;

        Bucket(double tokens, long lastRefill) {
            this.tokens = tokens;
            this.lastRefill = lastRefill;
        }
    }
}
