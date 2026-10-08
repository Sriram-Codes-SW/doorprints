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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who may use the owner page (docs/03 §12.1): one-time <b>setup links</b>, written to the server's log at every start
 * or made from a signed-in browser (*Add another browser*), and the <b>owner sessions</b> they are exchanged for. Both
 * are 32 random bytes kept as SHA-256 hashes.
 */
@Component
public class OwnerAuth {

    public static final Duration SETUP_LIFETIME = Duration.ofHours(1);
    public static final Duration SESSION_IDLE = Duration.ofDays(30);
    /** Last use is written at most this often. */
    static final Duration TOUCH_EVERY = Duration.ofMinutes(5);

    /**
     * An open browser session as the owner page lists it.
     */
    public record Session(UUID id, String label, Instant createdAt, Instant lastUsedAt) {
    }

    /** A signed-in session and its token, which becomes the cookie. */
    public record Opened(UUID id, String token) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public OwnerAuth(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A new one-time setup token, valid for {@link #SETUP_LIFETIME}. */
    public String newSetupToken() {
        var now = clock.instant();
        var token = Secrets.token();
        jdbc.sql("""
                        INSERT INTO owner_token (id, kind, token_hash, created_at, expires_at)
                        VALUES (:id, 'setup', :hash, :now, :expires)""")
                .param("id", UUID.randomUUID()).param("hash", Secrets.hash(token)).param("now", Timestamp.from(now))
                .param("expires", Timestamp.from(now.plus(SETUP_LIFETIME))).update();
        return token;
    }

    /** Uses a setup token once and opens a session for this browser. Empty if the token is unknown, used or expired. */
    @Transactional
    public Optional<Opened> exchange(String setupToken, String label) {
        if (setupToken == null || setupToken.isBlank()) return Optional.empty();
        var now = clock.instant();
        int used = jdbc.sql("""
                        UPDATE owner_token SET used_at = :now
                        WHERE token_hash = :hash AND kind = 'setup' AND used_at IS NULL AND revoked_at IS NULL
                          AND expires_at > :now""")
                .param("now", Timestamp.from(now)).param("hash", Secrets.hash(setupToken)).update();
        if (used != 1) return Optional.empty();
        var token = Secrets.token();
        var id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO owner_token (id, kind, token_hash, label, created_at, last_used_at, expires_at)
                        VALUES (:id, 'session', :hash, :label, :now, :now, :expires)""")
                .param("id", id).param("hash", Secrets.hash(token)).param("label", label(label))
                .param("now", Timestamp.from(now)).param("expires", Timestamp.from(now.plus(SESSION_IDLE))).update();
        return Optional.of(new Opened(id, token));
    }

    /** The session a cookie belongs to, if it is open; each use moves its expiry to 30 days from now. */
    public Optional<UUID> check(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) return Optional.empty();
        var now = clock.instant();
        record Hit(UUID id, Instant lastUsed) {
        }
        var hit = jdbc.sql("""
                        SELECT id, last_used_at FROM owner_token
                        WHERE token_hash = :hash AND kind = 'session' AND revoked_at IS NULL AND expires_at > :now""")
                .param("hash", Secrets.hash(sessionToken)).param("now", Timestamp.from(now))
                .query((rs, n) -> new Hit(rs.getObject("id", UUID.class), DeviceKeyStore.instant(rs, "last_used_at")))
                .optional();
        hit.ifPresent(h -> {
            if (h.lastUsed() == null || Duration.between(h.lastUsed(), now).compareTo(TOUCH_EVERY) >= 0) {
                jdbc.sql("UPDATE owner_token SET last_used_at = :now, expires_at = :expires WHERE id = :id")
                        .param("now", Timestamp.from(now)).param("expires", Timestamp.from(now.plus(SESSION_IDLE)))
                        .param("id", h.id()).update();
            }
        });
        return hit.map(Hit::id);
    }

    /** The open sessions (signed-in browsers), most recently used first. */
    public List<Session> sessions() {
        return jdbc.sql("""
                        SELECT id, label, created_at, last_used_at FROM owner_token
                        WHERE kind = 'session' AND revoked_at IS NULL AND expires_at > :now
                        ORDER BY last_used_at DESC NULLS LAST""")
                .param("now", Timestamp.from(clock.instant()))
                .query((rs, n) -> new Session(rs.getObject("id", UUID.class), rs.getString("label"),
                        DeviceKeyStore.instant(rs, "created_at"), DeviceKeyStore.instant(rs, "last_used_at")))
                .list();
    }

    /** Signs a browser out. False if there was no such open session. */
    public boolean revoke(UUID sessionId) {
        return jdbc.sql("""
                        UPDATE owner_token SET revoked_at = :now
                        WHERE id = :id AND kind = 'session' AND revoked_at IS NULL""")
                .param("now", Timestamp.from(clock.instant())).param("id", sessionId).update() == 1;
    }

    /** *Sign out everywhere else*: every session but this one; also unused setup links. Returns how many closed. */
    public int revokeAllBut(UUID keep) {
        return jdbc.sql("""
                        UPDATE owner_token SET revoked_at = :now
                        WHERE id <> :keep AND revoked_at IS NULL AND (kind = 'session' OR used_at IS NULL)""")
                .param("now", Timestamp.from(clock.instant())).param("keep", keep).update();
    }

    /** A short, printable description of the browser for the session list (from its User-Agent), or null. */
    static String label(String userAgent) {
        if (userAgent == null) return null;
        var ua = userAgent.replaceAll("\\p{Cntrl}", " ");
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Chrome/") ? "Chrome" : ua.contains("Safari/") ? "Safari" : "Browser";
        String os = ua.contains("Windows") ? "Windows" : ua.contains("Android") ? "Android"
                : ua.contains("iPhone") || ua.contains("iPad") ? "iOS" : ua.contains("Mac OS X") ? "macOS"
                : ua.contains("Linux") ? "Linux" : null;
        return os == null ? browser : browser + " on " + os;
    }

    /** Old closed rows go after 30 days. */
    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT10M")
    public void purgeOld() {
        jdbc.sql("DELETE FROM owner_token WHERE expires_at < :cutoff OR revoked_at < :cutoff")
                .param("cutoff", Timestamp.from(clock.instant().minus(Duration.ofDays(30)))).update();
    }
}
