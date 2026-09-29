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

import app.doorprints.server.config.AppProperties;
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

    public record SetupRequest(@NotBlank @Size(max = 100) String setup) {
    }

    public record CodeRequest(@NotBlank @Size(max = 20) String code) {
    }

    public record AiRequest(@NotNull Boolean allowed) {
    }

    public record Overview(List<DeviceKeyStore.Device> devices, List<OwnerAuth.Session> sessions,
                           UUID currentSession) {
    }

    public record Found(String deviceName, Instant createdAt, Instant expiresAt) {
    }

    /** An invite as the page shows it: the QR code (for phones) and the two links, until {@code expiresAt}. */
    public record InviteView(String qr, String appLink, String webLink, Instant expiresAt) {
    }

    public record LinkView(String link, Instant expiresAt) {
    }

    private final OwnerAuth auth;
    private final DeviceKeyStore devices;
    private final PairingService pairing;
    private final AppProperties props;
    /** Wrong codes typed on the owner page, per session: 5 a minute. */
    private final app.doorprints.server.ai.web.TokenBucketRateLimiter wrongCodes =
            new app.doorprints.server.ai.web.TokenBucketRateLimiter(5, 5);

    public OwnerApiController(OwnerAuth auth, DeviceKeyStore devices, PairingService pairing, AppProperties props) {
        this.auth = auth;
        this.devices = devices;
        this.pairing = pairing;
        this.props = props;
    }

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
                    "detail", "This link was already used or has expired. Restart the server for a new one."));
        }
        response.addHeader("Set-Cookie", COOKIE + "=" + opened.get().token() + "; Path=/owner; HttpOnly; SameSite=Strict"
                + "; Max-Age=" + OwnerAuth.SESSION_IDLE.toSeconds() + (request.isSecure() ? "; Secure" : ""));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/owner/api/overview")
    public Overview overview(HttpServletRequest request) {
        return new Overview(devices.list(), auth.sessions(), session(request));
    }

    @PostMapping("/owner/api/devices/{id}/revoke")
    public ResponseEntity<Void> revokeDevice(@PathVariable UUID id) {
        return devices.revoke(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

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

    @PostMapping("/owner/api/pairings/approve")
    public ResponseEntity<?> approve(@Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        return pairing.approve(body.code()) ? ResponseEntity.noContent().build() : wrongCode(request);
    }

    @PostMapping("/owner/api/pairings/deny")
    public ResponseEntity<?> deny(@Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        return pairing.deny(body.code()) ? ResponseEntity.noContent().build() : wrongCode(request);
    }

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
                Instant.now().plus(OwnerAuth.SETUP_LIFETIME));
    }

    @PostMapping("/owner/api/sessions/{id}/revoke")
    public ResponseEntity<Void> revokeSession(@PathVariable UUID id) {
        return auth.revoke(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/owner/api/sessions/revoke-others")
    public Map<String, Integer> revokeOthers(HttpServletRequest request) {
        return Map.of("closed", auth.revokeAllBut(session(request)));
    }

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
