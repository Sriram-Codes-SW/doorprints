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

import app.doorprints.server.ai.web.TokenBucketRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import app.doorprints.server.device.DeviceKeyStore;

/**
 * Single-user protection, <b>deny by default</b> (threat model F-20): every request must carry the shared key in the
 * {@code X-API-Key} header, except an explicit allowlist: {@code GET/HEAD /actuator/health[/**]} and CORS preflight
 * requests. {@code Authorization: Bearer <key>} is accepted too, because that is what most MCP clients can send.
 *
 * <p>Requests whose path is not canonical ({@code ;}, {@code %}, {@code \}, {@code //}, dot segments, see
 * {@link RequestPaths#isCanonical}) are rejected with 400 before anything else, so path tricks cannot make this filter
 * and Spring MVC disagree about which resource is being requested.
 *
 * <p>Failed attempts are logged (client address hashed, never the presented key: F-18) and throttled per client
 * address: after the burst is used up, further wrong keys get 429 instead of 401 (brute-force protection, F-05). A
 * correct key is never throttled here.
 *
 * <p>Keys must be at least {@link #MIN_KEY_LENGTH} characters (F-01). An optional second key ({@code APP_API_KEY_NEXT})
 * is accepted alongside the current one so the key can be rotated without downtime (SEC-017): set the new key as
 * {@code APP_API_KEY_NEXT}, move every client to it, then make it {@code APP_API_KEY} and clear
 * {@code APP_API_KEY_NEXT}.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    /** Set on a request made with a device key: its {@link DeviceKeyStore.Caller} (docs/03 §12.1). */
    public static final String DEVICE_ATTRIBUTE = "doorprints.device";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    /** Per-process salt so hashed client addresses in the logs can be correlated but not reversed by a table. */
    private static final byte[] LOG_SALT = randomSalt();

    /** Threat model F-01: shorter keys are refused at startup. 32 chars of hex/base64 is at least 128 bits. */
    public static final int MIN_KEY_LENGTH = 32;

    /** The current key and, during a rotation, the next one (SEC-017). Never empty. */
    private final List<byte[]> expected;
    private final TokenBucketRateLimiter failures;
    /** Device keys (ADR-25): the device a presented value belongs to, if it is an active device key. */
    private final Function<String, Optional<DeviceKeyStore.Caller>> devices;

    public ApiKeyFilter(String apiKey) {
        this(apiKey, null, new TokenBucketRateLimiter(10, 10));
    }

    /** @param failures bucket per client address for wrong or missing keys */
    public ApiKeyFilter(String apiKey, TokenBucketRateLimiter failures) {
        this(apiKey, null, failures);
    }

    /**
     * @param apiKey   the current key ({@code APP_API_KEY}), required
     * @param nextKey  optional second key ({@code APP_API_KEY_NEXT}); blank or {@code null} means none. While it is
     *                 set, both keys are accepted, so clients can be moved to the new key without downtime (SEC-017)
     * @param failures bucket per client address for wrong or missing keys
     * @throws IllegalStateException if a key is missing or shorter than {@link #MIN_KEY_LENGTH}
     */
    public ApiKeyFilter(String apiKey, String nextKey, TokenBucketRateLimiter failures) {
        this(apiKey, nextKey, failures, key -> Optional.empty());
    }

    /**
     * @param devices looks a presented value up among the device keys (docs/03 §12.1); the owner key is checked
     *                first, in constant time, as before
     */
    public ApiKeyFilter(String apiKey, String nextKey, TokenBucketRateLimiter failures,
                        Function<String, Optional<DeviceKeyStore.Caller>> devices) {
        validateKeys(apiKey, nextKey);
        this.devices = devices;
        var keys = new ArrayList<byte[]>(2);
        keys.add(apiKey.getBytes(StandardCharsets.UTF_8));
        if (hasText(nextKey)) keys.add(nextKey.getBytes(StandardCharsets.UTF_8));
        this.expected = List.copyOf(keys);
        this.failures = failures;
    }

    /**
     * Startup check for {@code APP_API_KEY} and the optional {@code APP_API_KEY_NEXT} (F-01). The message names the
     * variable and the rule but never includes the key itself.
     */
    public static void validateKeys(String apiKey, String nextKey) {
        if (!hasText(apiKey)) {
            throw new IllegalStateException("APP_API_KEY is not set. Set it to a random secret of at least "
                    + MIN_KEY_LENGTH + " characters (for example the output of `openssl rand -hex 32`) before "
                    + "starting the API.");
        }
        if (apiKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("APP_API_KEY is too short: it must be at least " + MIN_KEY_LENGTH
                    + " characters (for example the output of `openssl rand -hex 32`).");
        }
        if (hasText(nextKey) && nextKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("APP_API_KEY_NEXT is too short: it must be at least " + MIN_KEY_LENGTH
                    + " characters (for example the output of `openssl rand -hex 32`), or leave it empty.");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Constant-time check against every configured key: all keys are always compared (no early exit) and
     * {@link MessageDigest#isEqual} does not return early on the first differing byte, so response timing does not
     * reveal which key matched or how much of it.
     */
    private boolean matches(String given) {
        var presented = given.getBytes(StandardCharsets.UTF_8);
        boolean match = false;
        for (var key : expected) {
            match |= MessageDigest.isEqual(key, presented);
        }
        return match;
    }

    /**
     * Public without a key. Everything else, including unknown paths, needs the key. Pairing ({@code /api/pair/**}) is
     * for devices that have no key yet, and the owner page ({@code /owner}, its two files and {@code /owner/api/**})
     * has its own guard, {@code OwnerFilter}; both have their own rate limit (docs/03 §12.1).
     */
    static boolean isPublic(HttpServletRequest request, String path) {
        if (RequestPaths.isPreflight(request)) return true;
        var method = request.getMethod();
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        if (read && RequestPaths.isUnder(path, "/actuator/health")) return true;
        if ("POST".equals(method) && RequestPaths.isUnder(path, "/api/pair")) return true;
        if (RequestPaths.isUnder(path, "/owner/api")) return true;
        return read && (path.equals("/owner") || path.equals("/owner/owner.js") || path.equals("/owner/owner.css"));
    }

    /**
     * The gate for every API call. In order: a non-canonical path is refused with 400; a public path passes; the
     * owner key (current or next, compared in constant time) passes; a device key passes and the device is attached
     * to the request as {@link #DEVICE_ATTRIBUTE}; anything else is a failed attempt, answered 401, or 429 once the
     * client address has used up its failure allowance.
     * Failures are logged with a salted hash of the address, never the presented key.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var path = RequestPaths.path(request);
        if (!RequestPaths.isCanonical(path)) {
            log.warn("auth.reject reason=non-canonical-path client={} path={}", clientTag(request), RequestPaths.forLog(path));
            write(response, HttpServletResponse.SC_BAD_REQUEST, "Malformed request path");
            return;
        }
        if (isPublic(request, path)) {
            chain.doFilter(request, response);
            return;
        }
        var given = presentedKey(request);
        if (given != null && matches(given)) {
            chain.doFilter(request, response);
            return;
        }
        var device = given == null ? Optional.<DeviceKeyStore.Caller>empty() : devices.apply(given);
        if (device.isPresent()) {
            request.setAttribute(DEVICE_ATTRIBUTE, device.get());
            chain.doFilter(request, response);
            return;
        }
        var client = clientTag(request);
        var decision = failures.tryAcquire("fail:" + request.getRemoteAddr());
        if (!decision.allowed()) {
            log.warn("auth.throttled client={} path={} retryAfter={}s", client, RequestPaths.forLog(path),
                    decision.retryAfterSeconds());
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            write(response, 429, "Too many failed attempts, retry in " + decision.retryAfterSeconds() + "s");
            return;
        }
        log.warn("auth.fail reason={} client={} method={} path={}", given == null ? "missing-key" : "wrong-key",
                client, request.getMethod(), RequestPaths.forLog(path));
        write(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or wrong X-API-Key");
    }

    /**
     * Writes a small problem+json error body. Callers pass fixed text, never request data.
     */
    private static void write(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }

    /** The key the caller presented: X-API-Key, or else an {@code Authorization: Bearer} token. */
    public static String presentedKey(HttpServletRequest request) {
        var key = request.getHeader(HEADER);
        if (key != null) return key;
        var auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) return auth.substring(7).trim();
        return null;
    }

    private static byte[] randomSalt() {
        var salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /** Salted SHA-256 of the client address, first 12 hex chars: enough to spot a brute-force source in logs. */
    static String clientTag(HttpServletRequest request) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(LOG_SALT);
            md.update(String.valueOf(request.getRemoteAddr()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest(), 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
