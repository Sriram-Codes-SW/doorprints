package com.househunt.config;

import com.househunt.ai.web.TokenBucketRateLimiter;
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
import java.util.HexFormat;

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
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    /** Per-process salt so hashed client addresses in the logs can be correlated but not reversed by a table. */
    private static final byte[] LOG_SALT = randomSalt();

    private final byte[] expected;
    private final TokenBucketRateLimiter failures;

    public ApiKeyFilter(String apiKey) {
        this(apiKey, new TokenBucketRateLimiter(10, 10));
    }

    /** @param failures bucket per client address for wrong or missing keys */
    public ApiKeyFilter(String apiKey, TokenBucketRateLimiter failures) {
        this.expected = apiKey.getBytes(StandardCharsets.UTF_8);
        this.failures = failures;
    }

    /** Public without a key. Everything else, including unknown paths, needs the key. */
    static boolean isPublic(HttpServletRequest request, String path) {
        if (RequestPaths.isPreflight(request)) return true;
        var method = request.getMethod();
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        return read && RequestPaths.isUnder(path, "/actuator/health");
    }

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
        if (given != null && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
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
