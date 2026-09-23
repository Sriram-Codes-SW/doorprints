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
        limits = limits == null ? new Limits(null, null, null, null, null) : limits;
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

    /**
     * Request and storage limits (F-05, F-06).
     *
     * <p>{@code maxImportBytes} and {@code maxImportRows} apply to {@code POST /api/import} only: one backup file
     * holds every house at once, so it needs more room than a sync write, but still a hard cap (a backup of a few
     * thousand rows is well under a megabyte of JSON). The body cap defaults to
     * {@link com.househunt.backup.BackupFormat#MAX_DATA_JSON_BYTES} (16 MiB), the {@code data.json} limit of the
     * device readers, so any backup a phone or a browser accepts also restores to a server (docs/schemas/README.md
     * section 7). An operator may lower it; raising it only admits files no device could read back.
     */
    public record Limits(Integer maxJsonBytes, Integer maxPhotosPerHouse, Integer maxPhotoBytes,
                         Integer maxImportBytes, Integer maxImportRows) {
        public Limits {
            maxJsonBytes = positiveOr(maxJsonBytes, 256 * 1024);
            maxPhotosPerHouse = positiveOr(maxPhotosPerHouse, 20);
            maxPhotoBytes = positiveOr(maxPhotoBytes, 5 * 1024 * 1024);
            maxImportBytes = positiveOr(maxImportBytes,
                    Math.toIntExact(com.househunt.backup.BackupFormat.MAX_DATA_JSON_BYTES));
            maxImportRows = positiveOr(maxImportRows, 20_000);
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
