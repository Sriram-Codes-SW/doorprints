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

package app.doorprints.server.house;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * HTTP endpoints for houses, the main record the apps sync; the rules live in {@link HouseService}.
 */
@RestController
@RequestMapping("/api/houses")
public class HouseController {

    private final HouseService service;

    public HouseController(HouseService service) {
        this.service = service;
    }

    /** All live houses, or — with {@code since} — every change (including deletions) after that sync version. */
    @GetMapping
    public List<HouseDto> list(@RequestParam(required = false) Long since) {
        return service.list(since);
    }

    /**
     * One house, deleted ones included (as a tombstone).
     */
    @GetMapping("/{id}")
    public HouseDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    /**
     * Creates or updates a house; an older edit than the stored one is ignored and the stored house is returned.
     */
    @PutMapping("/{id}")
    public HouseDto upsert(@PathVariable UUID id, @Valid @RequestBody HouseDto body) {
        return service.upsert(id, body);
    }

    /**
     * Deletes a house and purges its private content.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Built-in MVC method validation (Spring 6.1+) turns a bad lat/lon/radius into 400 (F-19). */
    @GetMapping("/nearby")
    public List<HouseDto> nearby(@RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                                 @RequestParam @DecimalMin("-180") @DecimalMax("180") double lon,
                                 @RequestParam(defaultValue = "50") @Positive double radius) {
        return service.nearby(lat, lon, Math.min(radius, 5000));
    }

    /**
     * Live houses on the named street.
     */
    @GetMapping("/street")
    public List<HouseDto> onStreet(@RequestParam @NotBlank @Size(max = 200) String name) {
        return service.onStreet(name);
    }
}
