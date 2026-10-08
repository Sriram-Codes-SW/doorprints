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

package app.doorprints.server.config;

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
        Privacy privacy,
        /* The web app's address, for the owner page's connect links (APP_WEB_URL); docs/03 §12.1. */
        String webUrl,
        Pairing pairing,
        Owner owner) {

    public AppProperties {
        rateLimit = rateLimit == null ? new RateLimit(null, null, null, null) : rateLimit;
        limits = limits == null ? new Limits(null, null, null, null, null) : limits;
        sync = sync == null ? new Sync(null, null) : sync;
        privacy = privacy == null ? new Privacy(null) : privacy;
        webUrl = webUrl == null || webUrl.isBlank() ? "https://doorprints.web.app" : webUrl.replaceAll("/+$", "");
        pairing = pairing == null ? new Pairing(null, null, null, null) : pairing;
        owner = owner == null ? new Owner(null) : owner;
    }

    /**
     * Owner page settings. {@code setupLinkInLog} (OWNER_SETUP_LINK_IN_LOG, default false) is the owner's recovery
     * switch (S4b-BL-188, docs/03 section 12.1): when on, the one-hour setup link is written to the log at a start
     * even though a browser is signed in; the owner who lost their only signed-in browser uses it, then switches it
     * off again.
     */
    public record Owner(Boolean setupLinkInLog) {
        public Owner {
            setupLinkInLog = Boolean.TRUE.equals(setupLinkInLog);
        }
    }

    /**
     * The calls anyone can make without a key (pairing, signing in to the owner page), per client address (docs/03
     * section 12.1): a device polling every 3 seconds needs 20 a minute. {@code maxOpen} and {@code maxPerSource} cap
     * the unexpired pairing requests open in all and from one source (S4b-BL-161; PAIRING_MAX_OPEN, default 50, and
     * PAIRING_MAX_PER_SOURCE, default 5); unset means the default, below 1 stops startup. They are settings so the
     * release gate's API scan can lift them, as it lifts the rate limits (S4b-BL-191).
     */
    public record Pairing(Integer requestsPerMinute, Integer burst, Integer maxOpen, Integer maxPerSource) {
        public static final int DEFAULT_MAX_OPEN = 50;
        public static final int DEFAULT_MAX_PER_SOURCE = 5;

        public Pairing {
            requestsPerMinute = positiveOr(requestsPerMinute, 40);
            burst = positiveOr(burst, 20);
            maxOpen = atLeastOne(maxOpen, DEFAULT_MAX_OPEN, "app.pairing.max-open (PAIRING_MAX_OPEN)");
            maxPerSource = atLeastOne(maxPerSource, DEFAULT_MAX_PER_SOURCE,
                    "app.pairing.max-per-source (PAIRING_MAX_PER_SOURCE)");
        }

        private static int atLeastOne(Integer value, int fallback, String name) {
            if (value == null) return fallback;
            if (value < 1) throw new IllegalArgumentException(name + " must be at least 1, not " + value);
            return value;
        }
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
     * {@link app.doorprints.server.backup.BackupFormat#MAX_DATA_JSON_BYTES} (16 MiB), the {@code data.json} limit of the
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
                    Math.toIntExact(app.doorprints.server.backup.BackupFormat.MAX_DATA_JSON_BYTES));
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
