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

import app.doorprints.server.ai.config.AiProperties;
import app.doorprints.server.config.AppProperties;
import app.doorprints.server.secrets.GeminiKey;
import app.doorprints.server.secrets.ServerSecrets;
import app.doorprints.server.secrets.ServerSettings;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The owner page's calls (docs/03 §12.1, ADR-25), all under {@code /owner/api}. {@link OwnerFilter} has already
 * checked the session cookie (except for {@code POST /owner/api/session}, which opens one), the
 * {@value OwnerFilter#HEADER} header and the origin, and put the session id in the request.
 */
@RestController
public class OwnerApiController {

    static final String COOKIE = "dp_owner";

    /**
     * Body of the sign-in call.
     */
    public record SetupRequest(@NotBlank @Size(max = 100) String setup) {
    }

    /**
     * A user code typed by the owner.
     */
    public record CodeRequest(@NotBlank @Size(max = 20) String code) {
    }

    /**
     * The owner's per-device AI switch.
     */
    public record AiRequest(@NotNull Boolean allowed) {
    }

    /**
     * Everything the owner page lists: devices, signed-in browsers and which of them this is.
     */
    public record Overview(List<DeviceKeyStore.Device> devices, List<OwnerAuth.Session> sessions,
                           UUID currentSession) {
    }

    /**
     * The device waiting behind a typed code.
     */
    public record Found(String deviceName, Instant createdAt, Instant expiresAt) {
    }

    /** An invite as the page shows it: the QR code (for phones) and the two links, until {@code expiresAt}. */
    public record InviteView(String qr, String appLink, String webLink, Instant expiresAt) {
    }

    /**
     * A one-time link and when it stops working.
     */
    public record LinkView(String link, Instant expiresAt) {
    }

    /**
     * A Gemini key typed by the owner.
     */
    public record KeyRequest(@NotBlank @Size(min = 20, max = 200) String key) {
    }

    /**
     * The owner's switch to pause AI for the whole server.
     */
    public record PausedRequest(@NotNull Boolean paused) {
    }

    /**
     * AI on this server as the owner page shows it: whether AI is set up at all ({@code APP_AI_ENABLED}), which provider,
     * where the Gemini key comes from (the owner page, the settings file, or none) and its last four characters, and
     * whether the owner paused AI. Never the key.
     */
    public record AiView(boolean enabledOnServer, String provider, String keySource, String keyLast4,
                         Instant keySetAt, boolean paused) {
    }

    private final OwnerAuth auth;
    private final Clock clock;
    private final DeviceKeyStore devices;
    private final PairingService pairing;
    private final AppProperties props;
    private final GeminiKey geminiKey;
    private final ServerSecrets secrets;
    private final ServerSettings settings;
    private final boolean aiEnabled;
    private final String aiProvider;
    /** Wrong codes typed on the owner page, per session: 5 a minute. */
    private final app.doorprints.server.ai.web.TokenBucketRateLimiter wrongCodes =
            new app.doorprints.server.ai.web.TokenBucketRateLimiter(5, 5);

    /**
     * Takes the stores the page manages and whether AI is enabled and with which provider.
     */
    public OwnerApiController(OwnerAuth auth, DeviceKeyStore devices, PairingService pairing, AppProperties props,
                              GeminiKey geminiKey, ServerSecrets secrets, ServerSettings settings,
                              @Value("${app.ai.enabled:false}") boolean aiEnabled,
                              @Value("${app.ai.provider:aistudio}") String aiProvider, Clock clock) {
        this.auth = auth;
        this.clock = clock;
        this.devices = devices;
        this.pairing = pairing;
        this.props = props;
        this.geminiKey = geminiKey;
        this.secrets = secrets;
        this.settings = settings;
        this.aiEnabled = aiEnabled;
        this.aiProvider = AiProperties.normalizeProvider(aiProvider);
    }

    /**
     * Where AI stands on this server, without ever returning the key.
     */
    @GetMapping("/owner/api/ai")
    public AiView ai() {
        var stored = secrets.describe(GeminiKey.SECRET);
        return new AiView(aiEnabled, aiProvider, geminiKey.source().name().toLowerCase(java.util.Locale.ROOT),
                geminiKey.last4().orElse(null), stored.map(ServerSecrets.Stored::updatedAt).orElse(null),
                settings.aiPaused());
    }

    /** Sets the Gemini key, stored encrypted; it cannot be read back, only replaced or removed. */
    @PostMapping("/owner/api/ai/key")
    public ResponseEntity<?> setGeminiKey(@Valid @RequestBody KeyRequest body) {
        var key = body.key().strip();
        if (!key.matches("[\\x21-\\x7e]+")) {
            return ResponseEntity.badRequest().body(Map.of("status", 400,
                    "detail", "A Gemini key is one line of letters, digits and symbols, with no spaces."));
        }
        geminiKey.set(key);
        return ResponseEntity.noContent().build();
    }

    /**
     * Deletes the key set on the owner page.
     */
    @PostMapping("/owner/api/ai/key/remove")
    public ResponseEntity<Void> removeGeminiKey() {
        geminiKey.remove();
        return ResponseEntity.noContent().build();
    }

    /** Pauses or resumes AI for the whole server: every device and the owner key. */
    @PostMapping("/owner/api/ai/paused")
    public ResponseEntity<Void> pauseAi(@Valid @RequestBody PausedRequest body) {
        settings.put(ServerSettings.AI_PAUSED, Boolean.toString(body.paused()));
        return ResponseEntity.noContent().build();
    }

    /**
     * The session id that {@link OwnerFilter} put in the request.
     */
    private static UUID session(HttpServletRequest request) {
        return (UUID) request.getAttribute(OwnerFilter.SESSION_ATTRIBUTE);
    }

    /** Exchanges a one-time setup link for a session cookie on this browser. */
    @PostMapping("/owner/api/session")
    public ResponseEntity<?> signIn(@Valid @RequestBody SetupRequest body, HttpServletRequest request,
                                    HttpServletResponse response,
                                    @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        var opened = auth.exchange(body.setup(), userAgent);
        if (opened.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", 401,
                    "detail", "This link was already used or has expired. If no browser is signed in, restart the server for a new one; otherwise choose \"Add another browser\" in a signed-in browser."));
        }
        response.addHeader("Set-Cookie", COOKIE + "=" + opened.get().token() + "; Path=/owner; HttpOnly; SameSite=Strict"
                + "; Max-Age=" + OwnerAuth.SESSION_IDLE.toSeconds() + (request.isSecure() ? "; Secure" : ""));
        return ResponseEntity.noContent().build();
    }

    /**
     * The devices and browser sessions for the owner page.
     */
    @GetMapping("/owner/api/overview")
    public Overview overview(HttpServletRequest request) {
        return new Overview(devices.list(), auth.sessions(), session(request));
    }

    /**
     * Revokes a device key at once; 404 if there is no such active device.
     */
    @PostMapping("/owner/api/devices/{id}/revoke")
    public ResponseEntity<Void> revokeDevice(@PathVariable UUID id) {
        return devices.revoke(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Turns AI on or off for one device.
     */
    @PostMapping("/owner/api/devices/{id}/ai")
    public ResponseEntity<Void> deviceAi(@PathVariable UUID id, @Valid @RequestBody AiRequest body) {
        return devices.setAiAllowed(id, body.allowed()) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** The owner typed a code: which device asked for it. A wrong code counts against 5 a minute per session. */
    @PostMapping("/owner/api/pairings/find")
    public ResponseEntity<?> find(@Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        var found = pairing.findPending(body.code());
        if (found.isPresent()) {
            var p = found.get();
            return ResponseEntity.ok(new Found(p.deviceName(), p.createdAt(), p.expiresAt()));
        }
        return wrongCode(request);
    }

    /**
     * The owner approves the device waiting behind this code. A wrong code counts against the per-session limit.
     */
    @PostMapping("/owner/api/pairings/approve")
    public ResponseEntity<?> approve(@Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        return pairing.approve(body.code()) ? ResponseEntity.noContent().build() : wrongCode(request);
    }

    /**
     * The owner refuses the device waiting behind this code. A wrong code counts against the per-session limit.
     */
    @PostMapping("/owner/api/pairings/deny")
    public ResponseEntity<?> deny(@Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        return pairing.deny(body.code()) ? ResponseEntity.noContent().build() : wrongCode(request);
    }

    /**
     * The answer for a code that matches no waiting device: 404, or 429 once the session has typed too many wrong
     * ones, which stops guessing of the 8-symbol code.
     */
    private ResponseEntity<?> wrongCode(HttpServletRequest request) {
        var decision = wrongCodes.tryAcquire("owner:" + session(request));
        if (!decision.allowed()) {
            return ResponseEntity.status(429).header("Retry-After", Long.toString(decision.retryAfterSeconds()))
                    .body(Map.of("status", 429, "detail", "Too many wrong codes. Wait a minute and try again."));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", 404,
                "detail", "No device is waiting with this code. Check it, or start again on the device."));
    }

    /** A one-time invite: the QR code and the links carry this server's address as the page sees it. */
    @PostMapping("/owner/api/invites")
    public InviteView invite(HttpServletRequest request) {
        var server = OwnerFilter.origin(request);
        var invite = pairing.invite();
        var query = "server=" + enc(server) + "&invite=" + enc(invite.token());
        var appLink = "doorprints://connect?" + query;
        var webLink = props.webUrl() + "/connect?" + query;
        return new InviteView(QrCodes.svgDataUrl(appLink), appLink, webLink, invite.expiresAt());
    }

    /** *Add another browser*: a one-time link to this page, valid for an hour. */
    @PostMapping("/owner/api/browser-links")
    public LinkView browserLink(HttpServletRequest request) {
        var token = auth.newSetupToken();
        return new LinkView(OwnerFilter.origin(request) + "/owner#setup=" + token,
                clock.instant().plus(OwnerAuth.SETUP_LIFETIME));
    }

    /**
     * Signs one browser out; 404 if it is not an open session.
     */
    @PostMapping("/owner/api/sessions/{id}/revoke")
    public ResponseEntity<Void> revokeSession(@PathVariable UUID id) {
        return auth.revoke(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Signs out every other browser and cancels unused setup links; returns how many were closed.
     */
    @PostMapping("/owner/api/sessions/revoke-others")
    public Map<String, Integer> revokeOthers(HttpServletRequest request) {
        return Map.of("closed", auth.revokeAllBut(session(request)));
    }

    /**
     * Ends this browser's session and clears its cookie.
     */
    @PostMapping("/owner/api/sign-out")
    public ResponseEntity<Void> signOut(HttpServletRequest request, HttpServletResponse response) {
        auth.revoke(session(request));
        response.addHeader("Set-Cookie", COOKIE + "=; Path=/owner; HttpOnly; SameSite=Strict; Max-Age=0"
                + (request.isSecure() ? "; Secure" : ""));
        return ResponseEntity.noContent().build();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
