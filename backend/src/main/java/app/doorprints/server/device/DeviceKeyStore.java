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

package app.doorprints.server.device;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The device keys (docs/03 §12.1, ADR-25): one per connected app install, kept as a SHA-256 hash, with a name, the
 * owner's per-device AI switch (off for a new device) and when it was added, last used and revoked.
 */
@Component
public class DeviceKeyStore {

    /** How often a key's last use is written at most: a sync burst should not turn into a write per request. */
    static final Duration TOUCH_EVERY = Duration.ofMinutes(5);

    /** What the owner page shows of a device. Never the key. */
    public record Device(UUID id, String name, String keyLast4, String via, boolean aiAllowed, Instant createdAt,
                         Instant lastUsedAt, Instant revokedAt) {
        public boolean active() {
            return revokedAt == null;
        }
    }

    /** A device key checked on a request: which device it is and whether its AI switch is on. */
    public record Caller(UUID deviceId, boolean aiAllowed) {
    }

    /** A key made for a device: the key itself goes to the device once; the server keeps its hash. */
    public record Issued(UUID deviceId, String key) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Map<String, Instant> lastTouched = new ConcurrentHashMap<>();

    public DeviceKeyStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Makes a key for a new device named {@code name}, connected {@code via} a code or an invite. */
    @Transactional
    public Issued issue(String name, String via) {
        var key = Secrets.deviceKey();
        var id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO device_key (id, name, key_hash, key_last4, via, ai_allowed, created_at)
                        VALUES (:id, :name, :hash, :last4, :via, false, :now)""")
                .param("id", id).param("name", name).param("hash", Secrets.hash(key))
                .param("last4", Secrets.last4(key)).param("via", via).param("now", Timestamp.from(clock.instant()))
                .update();
        return new Issued(id, key);
    }

    /** The active device a presented value belongs to, if it is a device key that is not revoked. */
    public Optional<Caller> check(String presented) {
        if (presented == null || !presented.startsWith(Secrets.DEVICE_KEY_PREFIX)) return Optional.empty();
        var hash = Secrets.hash(presented);
        var caller = jdbc.sql("SELECT id, ai_allowed FROM device_key WHERE key_hash = :hash AND revoked_at IS NULL")
                .param("hash", hash)
                .query((rs, n) -> new Caller(rs.getObject("id", UUID.class), rs.getBoolean("ai_allowed")))
                .optional();
        caller.ifPresent(c -> touch(c.deviceId(), HexFormat.of().formatHex(hash)));
        return caller;
    }

    /**
     * Records a device's last use, at most once per {@link #TOUCH_EVERY} per key, so a burst of syncs is not a burst
     * of writes.
     */
    private void touch(UUID id, String hashHex) {
        var now = clock.instant();
        var last = lastTouched.get(hashHex);
        if (last != null && Duration.between(last, now).compareTo(TOUCH_EVERY) < 0) return;
        lastTouched.put(hashHex, now);
        jdbc.sql("UPDATE device_key SET last_used_at = :now WHERE id = :id")
                .param("now", Timestamp.from(now)).param("id", id).update();
    }

    /** Every device, newest first, revoked ones included (the owner page shows them greyed). */
    public List<Device> list() {
        return jdbc.sql("SELECT * FROM device_key ORDER BY created_at DESC").query(DeviceKeyStore::device).list();
    }

    /** Revokes a device: its key stops working at once. False if there was no such active device. */
    public boolean revoke(UUID id) {
        return jdbc.sql("UPDATE device_key SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
                .param("now", Timestamp.from(clock.instant())).param("id", id).update() == 1;
    }

    /** The owner's per-device AI switch. False if there was no such active device. */
    public boolean setAiAllowed(UUID id, boolean allowed) {
        return jdbc.sql("UPDATE device_key SET ai_allowed = :allowed WHERE id = :id AND revoked_at IS NULL")
                .param("allowed", allowed).param("id", id).update() == 1;
    }

    private static Device device(ResultSet rs, int n) throws SQLException {
        return new Device(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("key_last4"),
                rs.getString("via"), rs.getBoolean("ai_allowed"), instant(rs, "created_at"),
                instant(rs, "last_used_at"), instant(rs, "revoked_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
