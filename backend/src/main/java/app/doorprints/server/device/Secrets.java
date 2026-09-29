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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * The random values of device pairing and the owner page (docs/03 §12.1): tokens, device keys and the short user code,
 * and the SHA-256 hash that is all the database keeps of each.
 */
public final class Secrets {

    /** Prefix of every device key, so a leaked one is easy to recognise and to search for. */
    public static final String DEVICE_KEY_PREFIX = "dpk_";

    /**
     * The user code's letters and digits: no 0/O, 1/I/L or 5/S look-alikes, so a code read off a phone is typed right.
     * 30 symbols; 8 of them give about 6.6 * 10^11 codes.
     */
    static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRTUVWXYZ23456789";
    public static final int CODE_LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {
    }

    /** 32 random bytes, base64url without padding (43 characters). */
    public static String token() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A new device key: {@link #DEVICE_KEY_PREFIX} and a {@link #token()}. */
    public static String deviceKey() {
        return DEVICE_KEY_PREFIX + token();
    }

    /** A new user code of {@link #CODE_LENGTH} symbols from {@link #CODE_ALPHABET}, stored without the dash. */
    public static String userCode() {
        var sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        return sb.toString();
    }

    /** As shown to people: {@code K7MQ-4XRD}. */
    public static String displayCode(String code) {
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    /**
     * A code as typed: upper-cased, spaces and dashes dropped. Returns null unless it is exactly {@link #CODE_LENGTH}
     * symbols of the alphabet, so a typo is refused before any lookup.
     */
    public static String normalizeCode(String typed) {
        if (typed == null) return null;
        var code = typed.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        if (code.length() != CODE_LENGTH) return null;
        for (int i = 0; i < code.length(); i++) {
            if (CODE_ALPHABET.indexOf(code.charAt(i)) < 0) return null;
        }
        return code;
    }

    /** SHA-256 of the UTF-8 bytes: the only form in which the database keeps a secret. */
    public static byte[] hash(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The last four characters, which the owner page shows to tell keys apart. */
    public static String last4(String secret) {
        return secret.substring(Math.max(0, secret.length() - 4));
    }
}
