package com.househunt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * {@code app.*} settings (except {@code app.ai.*}, see AiProperties). Nested groups fall back to safe defaults when
 * they are missing, so tests can build the record with {@code null}s.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String apiKey,
        /* Optional second key accepted during a rotation (APP_API_KEY_NEXT, SEC-017); blank means none. */
        String apiKeyNext,
        List<String> corsOrigins,
        RateLimit rateLimit,
        Limits limits,
        Sync sync,
        Privacy privacy) {

    public AppProperties {
        rateLimit = rateLimit == null ? new RateLimit(null, null, null, null) : rateLimit;
        limits = limits == null ? new Limits(null, null, null) : limits;
        sync = sync == null ? new Sync(null, null) : sync;
        privacy = privacy == null ? new Privacy(null) : privacy;
    }

    /**
     * Per client address over every path (F-05). Defaults are generous for one person syncing a phone after a day
     * offline (one request per changed row) but stop floods from burning free-tier CPU.
     */
    public record RateLimit(Integer requestsPerMinute, Integer burst, Integer authFailuresPerMinute,
                            Integer authFailureBurst) {
        public RateLimit {
            requestsPerMinute = positiveOr(requestsPerMinute, 600);
            burst = positiveOr(burst, 300);
            authFailuresPerMinute = positiveOr(authFailuresPerMinute, 10);
            authFailureBurst = positiveOr(authFailureBurst, 10);
        }
    }

    /** Request and storage limits (F-05, F-06). */
    public record Limits(Integer maxJsonBytes, Integer maxPhotosPerHouse, Integer maxPhotoBytes) {
        public Limits {
            maxJsonBytes = positiveOr(maxJsonBytes, 256 * 1024);
            maxPhotosPerHouse = positiveOr(maxPhotosPerHouse, 20);
            maxPhotoBytes = positiveOr(maxPhotoBytes, 5 * 1024 * 1024);
        }
    }

    /**
     * Client clock handling (F-08): {@code updatedAt} later than server now + {@code maxClockSkewSeconds} is clamped
     * to server now; dates more than {@code maxFutureDays} ahead (or before 2000) are rejected with 400.
     */
    public record Sync(Integer maxClockSkewSeconds, Integer maxFutureDays) {
        public Sync {
            maxClockSkewSeconds = positiveOr(maxClockSkewSeconds, 300);
            maxFutureDays = positiveOr(maxFutureDays, 365);
        }
    }

    /** Tombstones (deleted rows kept so other devices learn about the delete) are purged after this many days. */
    public record Privacy(Integer tombstoneRetentionDays) {
        public Privacy {
            tombstoneRetentionDays = positiveOr(tombstoneRetentionDays, 90);
        }
    }

    private static int positiveOr(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
