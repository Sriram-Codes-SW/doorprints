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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Connecting a device without pasting a key (docs/03 §12.1, ADR-25).
 *
 * <p><b>By code</b> (RFC 8628 in small): the app {@link #start starts} a request and shows the user code; the owner
 * types that code on the owner page ({@link #findPending}, then {@link #approve} or {@link #deny}); the app
 * {@link #poll polls} with its poll token and receives its device key once, on the first poll after the approval.
 * The key is made at that moment, so no plain key is ever stored.
 *
 * <p><b>By invite</b> (QR code or link): the owner page {@link #invite makes} a one-time invite; the app
 * {@link #redeem redeems} it for its device key.
 */
@Service
public class PairingService {

    public static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    public static final Duration INVITE_LIFETIME = Duration.ofMinutes(10);
    public static final int POLL_INTERVAL_SECONDS = 3;
    /** Any constant: it only serialises the starts (a transaction-scoped advisory lock). */
    private static final long START_LOCK = 0x70616972L;
    public static final int MAX_NAME_LENGTH = 60;

    public record Started(String userCode, String pollToken, long expiresIn, int interval) {
    }

    public enum PollStatus { PENDING, APPROVED, DENIED, EXPIRED }

    /** The answer to a poll; {@code deviceKey} only with {@code APPROVED}, and only once. */
    public record Polled(PollStatus status, String deviceKey) {
    }

    /** A request as the owner page shows it after the owner typed its code. */
    public record Pending(UUID id, String deviceName, Instant createdAt, Instant expiresAt) {
    }

    public record Invite(String token, Instant expiresAt) {
    }

    private final JdbcClient jdbc;
    private final DeviceKeyStore devices;
    private final Clock clock;
    /** Per run, so a stored client hash cannot be matched against a table of addresses. */
    private final String clientSalt = Secrets.token();

    public PairingService(JdbcClient jdbc, DeviceKeyStore devices, Clock clock) {
        this.jdbc = jdbc;
        this.devices = devices;
        this.clock = clock;
    }

    /** A device name as the owner page shows it: trimmed, control characters dropped, at most 60 characters. */
    public static String cleanName(String name) {
        var cleaned = name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").strip().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) cleaned = "Device";
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }

    /**
     * Opens a pairing request for a device and returns the code to show the owner and the token the device polls
     * with. Only the poll token's hash is stored, and of the client address only a salted hash (to cap the requests per
     * source). At most {@link PairingAdmission#MAX_OPEN} unexpired requests are open, and
     * {@link PairingAdmission#MAX_OPEN_PER_CLIENT} per source: a start over a cap is refused with
     * {@link PairingBusyException} and no open request is touched, so a flood cannot push out the code a person is
     * typing (S4b-BL-161). The table stays bounded: a row is added only while fewer than 50 are open, and the hourly
     * {@link #purgeExpired} removes old ones. The user code is drawn again until no open request uses it.
     */
    @Transactional
    public Started start(String deviceName, String clientAddress) {
        var now = clock.instant();
        // One start at a time, so two starts cannot both pass the check at 49 open requests.
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", START_LOCK).query().singleValue();
        var client = Secrets.hash(clientSalt + "|" + (clientAddress == null ? "" : clientAddress));
        var open = jdbc.sql("""
                        SELECT count(*) AS total, min(expires_at) AS first_expiry,
                               count(*) FILTER (WHERE client_hash = :client) AS from_client,
                               min(expires_at) FILTER (WHERE client_hash = :client) AS client_first_expiry
                        FROM pairing_request WHERE status = 'pending' AND expires_at > :now""")
                .param("client", client).param("now", Timestamp.from(now))
                .query((rs, n) -> new PairingAdmission.Open(rs.getInt("total"),
                        DeviceKeyStore.instant(rs, "first_expiry"), rs.getInt("from_client"),
                        DeviceKeyStore.instant(rs, "client_first_expiry")))
                .single();
        PairingAdmission.retryAfterSeconds(open, now).ifPresent(wait -> {
            throw new PairingBusyException(wait);
        });
        String code;
        do {
            code = Secrets.userCode();
        } while (findPendingRow(code, now).isPresent());
        var poll = Secrets.token();
        jdbc.sql("""
                        INSERT INTO pairing_request (id, user_code, poll_hash, device_name, status, created_at, expires_at,
                                                     client_hash)
                        VALUES (:id, :code, :poll, :name, 'pending', :now, :expires, :client)""")
                .param("id", UUID.randomUUID()).param("code", code).param("poll", Secrets.hash(poll))
                .param("name", cleanName(deviceName)).param("now", Timestamp.from(now))
                .param("expires", Timestamp.from(now.plus(CODE_LIFETIME))).param("client", client).update();
        return new Started(Secrets.displayCode(code), poll, CODE_LIFETIME.toSeconds(), POLL_INTERVAL_SECONDS);
    }

    /**
     * A pairing request row as read back.
     */
    private record Row(UUID id, String status, String deviceName, Instant createdAt, Instant expiresAt) {
    }

    /**
     * The open, unexpired request with this user code.
     */
    private Optional<Row> findPendingRow(String code, Instant now) {
        return jdbc.sql("""
                        SELECT id, status, device_name, created_at, expires_at FROM pairing_request
                        WHERE user_code = :code AND status = 'pending' AND expires_at > :now""")
                .param("code", code).param("now", Timestamp.from(now))
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getString("device_name"), DeviceKeyStore.instant(rs, "created_at"),
                        DeviceKeyStore.instant(rs, "expires_at")))
                .optional();
    }

    /** The open request with the code the owner typed, if any. */
    public Optional<Pending> findPending(String typedCode) {
        var code = Secrets.normalizeCode(typedCode);
        if (code == null) return Optional.empty();
        return findPendingRow(code, clock.instant())
                .map(r -> new Pending(r.id(), r.deviceName(), r.createdAt(), r.expiresAt()));
    }

    /** The owner approves the open request with this code. False if there is none (wrong, used or expired code). */
    public boolean approve(String typedCode) {
        return decide(typedCode, "approved");
    }

    /**
     * The owner refuses the open request with this code. False if there is none.
     */
    public boolean deny(String typedCode) {
        return decide(typedCode, "denied");
    }

    /**
     * Sets the status of the open request with this code; only a pending, unexpired one can be decided, once.
     */
    private boolean decide(String typedCode, String status) {
        var code = Secrets.normalizeCode(typedCode);
        if (code == null) return false;
        return jdbc.sql("""
                        UPDATE pairing_request SET status = :status
                        WHERE user_code = :code AND status = 'pending' AND expires_at > :now""")
                .param("status", status).param("code", code).param("now", Timestamp.from(clock.instant()))
                .update() == 1;
    }

    /** The device asks how its request stands. An unknown token reads as expired, like a request already cleaned up. */
    @Transactional
    public Polled poll(String pollToken) {
        if (pollToken == null || pollToken.isBlank()) return new Polled(PollStatus.EXPIRED, null);
        var hash = Secrets.hash(pollToken);
        var row = jdbc.sql("""
                        SELECT id, status, device_name, created_at, expires_at FROM pairing_request
                        WHERE poll_hash = :hash FOR UPDATE""")
                .param("hash", hash)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getString("device_name"), DeviceKeyStore.instant(rs, "created_at"),
                        DeviceKeyStore.instant(rs, "expires_at")))
                .optional();
        if (row.isEmpty()) return new Polled(PollStatus.EXPIRED, null);
        var r = row.get();
        switch (r.status()) {
            case "approved" -> {
                var issued = devices.issue(r.deviceName(), "code");
                jdbc.sql("UPDATE pairing_request SET status = 'claimed', device_id = :device WHERE id = :id")
                        .param("device", issued.deviceId()).param("id", r.id()).update();
                return new Polled(PollStatus.APPROVED, issued.key());
            }
            case "denied" -> {
                return new Polled(PollStatus.DENIED, null);
            }
            case "pending" -> {
                return r.expiresAt().isAfter(clock.instant()) ? new Polled(PollStatus.PENDING, null)
                        : new Polled(PollStatus.EXPIRED, null);
            }
            default -> {
                // 'claimed': the key was handed over already; it is never sent twice.
                return new Polled(PollStatus.EXPIRED, null);
            }
        }
    }

    /** A one-time invite for the owner page's QR code and link. */
    public Invite invite() {
        var now = clock.instant();
        var token = Secrets.token();
        var expires = now.plus(INVITE_LIFETIME);
        jdbc.sql("""
                        INSERT INTO pairing_invite (id, token_hash, created_at, expires_at)
                        VALUES (:id, :hash, :now, :expires)""")
                .param("id", UUID.randomUUID()).param("hash", Secrets.hash(token))
                .param("now", Timestamp.from(now)).param("expires", Timestamp.from(expires)).update();
        return new Invite(token, expires);
    }

    /** The device redeems an invite for its key. Empty if the invite is unknown, used or expired. */
    @Transactional
    public Optional<DeviceKeyStore.Issued> redeem(String inviteToken, String deviceName) {
        if (inviteToken == null || inviteToken.isBlank()) return Optional.empty();
        var now = clock.instant();
        var id = jdbc.sql("""
                        SELECT id FROM pairing_invite
                        WHERE token_hash = :hash AND used_at IS NULL AND expires_at > :now FOR UPDATE""")
                .param("hash", Secrets.hash(inviteToken)).param("now", Timestamp.from(now))
                .query(UUID.class).optional();
        if (id.isEmpty()) return Optional.empty();
        var issued = devices.issue(cleanName(deviceName), "invite");
        jdbc.sql("UPDATE pairing_invite SET used_at = :now, device_id = :device WHERE id = :id")
                .param("now", Timestamp.from(now)).param("device", issued.deviceId()).param("id", id.get()).update();
        return Optional.of(issued);
    }

    /** Old requests and invites go after a day; they are useless once expired and hold only names and hashes. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void purgeExpired() {
        var cutoff = Timestamp.from(clock.instant().minus(Duration.ofDays(1)));
        jdbc.sql("DELETE FROM pairing_request WHERE expires_at < :cutoff").param("cutoff", cutoff).update();
        jdbc.sql("DELETE FROM pairing_invite WHERE expires_at < :cutoff").param("cutoff", cutoff).update();
    }
}
