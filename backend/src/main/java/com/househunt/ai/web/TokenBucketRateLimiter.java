package com.househunt.ai.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Minimal in-memory token bucket (no Bucket4j): each key gets {@code capacity} tokens that refill continuously at
 * {@code refillPerMinute}. One instance per JVM is plenty for a single-user app on one small host; if the API is
 * ever scaled out, move this to Postgres or Redis.
 */
public class TokenBucketRateLimiter {

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

    private static final class Bucket {
        double tokens;
        long lastRefill;

        Bucket(double tokens, long lastRefill) {
            this.tokens = tokens;
            this.lastRefill = lastRefill;
        }
    }
}
