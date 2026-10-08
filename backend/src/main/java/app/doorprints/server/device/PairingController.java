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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

/**
 * How an app connects without a pasted key (docs/03 §12.1, ADR-25). Public: the caller has no key yet. Rate-limited
 * per address by {@link PairingRateLimitFilter}.
 */
@RestController
public class PairingController {

    /**
     * Body of the start call: the name the device shows the owner.
     */
    public record StartRequest(@Size(max = 200) String deviceName) {
    }

    /**
     * Body of the poll call.
     */
    public record PollRequest(@NotBlank @Size(max = 100) String pollToken) {
    }

    /**
     * Body of the redeem call: the invite and a device name.
     */
    public record RedeemRequest(@NotBlank @Size(max = 100) String invite, @Size(max = 200) String deviceName) {
    }

    /** {@code status} is one of pending, approved, denied, expired; {@code deviceKey} only with approved. */
    public record PollResponse(String status, String deviceKey) {
    }

    /**
     * The device key, shown to the device only this once.
     */
    public record KeyResponse(String deviceKey) {
    }

    private final PairingService pairing;

    public PairingController(PairingService pairing) {
        this.pairing = pairing;
    }

    /**
     * A device asks to connect by code; the owner approves it on the owner page.
     */
    @PostMapping("/api/pair/start")
    public PairingService.Started start(@Valid @RequestBody StartRequest body) {
        return pairing.start(body.deviceName());
    }

    /**
     * The device checks whether the owner approved; the device key comes with the first approved answer only.
     */
    @PostMapping("/api/pair/poll")
    public PollResponse poll(@Valid @RequestBody PollRequest body) {
        var polled = pairing.poll(body.pollToken());
        return new PollResponse(polled.status().name().toLowerCase(Locale.ROOT), polled.deviceKey());
    }

    /**
     * The device connects with a one-time invite; 410 when the invite was used or has expired.
     */
    @PostMapping("/api/pair/redeem")
    public ResponseEntity<?> redeem(@Valid @RequestBody RedeemRequest body) {
        return pairing.redeem(body.invite(), body.deviceName())
                .<ResponseEntity<?>>map(issued -> ResponseEntity.ok(new KeyResponse(issued.key())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.GONE)
                        .body(Map.of("status", 410, "detail", "This invite was already used or has expired.")));
    }
}
