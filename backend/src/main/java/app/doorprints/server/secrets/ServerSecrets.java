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

package app.doorprints.server.secrets;

import app.doorprints.server.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Secrets the owner sets on the owner page, kept encrypted in the database (docs/03 §12.1, ADR-25): today the Gemini
 * key. AES-256-GCM with a random 12-byte nonce per write and the secret's name as associated data, under a key derived
 * (HKDF-SHA256) from the server's owner key, {@code APP_API_KEY}: a copy of the database alone does not reveal a secret,
 * and the owner has no extra setting to keep.
 *
 * <p>Rotating the owner key (docs/08 §5.1): while {@code APP_API_KEY_NEXT} is set, a secret written under the current
 * key is re-encrypted under the next one at start, and reads try both; once the next key becomes {@code APP_API_KEY},
 * the secrets read with it. A secret that no key opens (the owner key was replaced without a rotation) reads as absent,
 * with a warning, and the owner sets it again.
 */
@Component
public class ServerSecrets {

    private static final Logger log = LoggerFactory.getLogger(ServerSecrets.class);
    private static final byte[] INFO = "doorprints/server-secrets/v1".getBytes(StandardCharsets.UTF_8);
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * What may be shown about a stored secret: its last four characters and when it was set.
     */
    public record Stored(String last4, java.time.Instant updatedAt) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    /** Keys that may open a secret, the one used for writing first. */
    private final List<SecretKeySpec> keys;

    /**
     * Derives the encryption keys: the next owner key first (while a rotation is in progress), then the current one.
     * Writes use the first, reads try each in turn.
     */
    public ServerSecrets(JdbcClient jdbc, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.clock = clock;
        var ks = new ArrayList<SecretKeySpec>(2);
        var next = props.apiKeyNext();
        if (next != null && !next.isBlank()) ks.add(derive(next));
        ks.add(derive(props.apiKey()));
        this.keys = List.copyOf(ks);
    }

    /** HKDF-SHA256 (RFC 5869) with an empty salt: extract, then one expand block of 32 bytes. */
    static SecretKeySpec derive(String ownerKey) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            var prk = mac.doFinal(ownerKey.getBytes(StandardCharsets.UTF_8));
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(INFO);
            mac.update((byte) 1);
            return new SecretKeySpec(mac.doFinal(), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Encrypts a value with a fresh random nonce and binds it to the secret's name. The result is the nonce followed
     * by the ciphertext and tag.
     */
    static byte[] seal(SecretKeySpec key, String name, String value) {
        try {
            var nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            var sealed = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(NONCE + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The value, or empty when this key does not open it (wrong key, or the stored bytes were changed). */
    static Optional<String> open(SecretKeySpec key, String name, byte[] stored) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, NONCE));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return Optional.of(new String(cipher.doFinal(stored, NONCE, stored.length - NONCE), StandardCharsets.UTF_8));
        } catch (AEADBadTagException e) {
            return Optional.empty();
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Stores or replaces a secret, encrypted under the newest key, together with its last four characters for
     * display.
     */
    public void put(String name, String value) {
        jdbc.sql("""
                        INSERT INTO server_secret (name, ciphertext, last4, updated_at) VALUES (:name, :c, :last4, :now)
                        ON CONFLICT (name) DO UPDATE SET ciphertext = :c, last4 = :last4, updated_at = :now""")
                .param("name", name).param("c", seal(keys.get(0), name, value))
                .param("last4", value.substring(Math.max(0, value.length() - 4)))
                .param("now", Timestamp.from(clock.instant())).update();
    }

    /**
     * The decrypted secret, or empty when it is not set or no configured key opens it (logged as a warning, never the
     * value).
     */
    public Optional<String> get(String name) {
        var stored = jdbc.sql("SELECT ciphertext FROM server_secret WHERE name = :name").param("name", name)
                .query(byte[].class).optional();
        if (stored.isEmpty()) return Optional.empty();
        for (var key : keys) {
            var value = open(key, name, stored.get());
            if (value.isPresent()) return value;
        }
        log.warn("secrets.unreadable name={} (the owner key changed without a rotation; set it again on the owner page)",
                name);
        return Optional.empty();
    }

    /** What the owner page shows of a secret: its last four characters and when it was set. Never the value. */
    public Optional<Stored> describe(String name) {
        return jdbc.sql("SELECT last4, updated_at FROM server_secret WHERE name = :name").param("name", name)
                .query((rs, n) -> new Stored(rs.getString("last4"), rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    /**
     * Deletes a secret; true if there was one.
     */
    public boolean remove(String name) {
        return jdbc.sql("DELETE FROM server_secret WHERE name = :name").param("name", name).update() == 1;
    }

    /**
     * During a rotation (both owner keys set): every secret the current key opens is written again under the next key,
     * once the database is migrated and the server has started.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void reencryptUnderNextKey() {
        if (keys.size() < 2) return;
        record Row(String name, byte[] c) {
        }
        var rows = jdbc.sql("SELECT name, ciphertext FROM server_secret")
                .query((rs, n) -> new Row(rs.getString("name"), rs.getBytes("ciphertext"))).list();
        for (var row : rows) {
            if (open(keys.get(0), row.name(), row.c()).isPresent()) continue;
            open(keys.get(1), row.name(), row.c()).ifPresent(value -> jdbc.sql(
                            "UPDATE server_secret SET ciphertext = :c WHERE name = :name")
                    .param("c", seal(keys.get(0), row.name(), value)).param("name", row.name()).update());
        }
    }
}
