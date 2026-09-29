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

package app.doorprints.server.photo;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class PhotoController {

    private final PhotoService service;

    public PhotoController(PhotoService service) {
        this.service = service;
    }

    /** Live photo ids of a house. */
    @GetMapping("/houses/{houseId}/photos")
    public List<UUID> list(@PathVariable UUID houseId) {
        return service.liveIds(houseId);
    }

    /** Photo metadata changes (new photos and delete tombstones) after a sync version, for offline clients. */
    @GetMapping("/photos")
    public List<PhotoDto> changes(@RequestParam long since) {
        return service.changesSince(since);
    }

    /**
     * The client picks the photo id so an upload retried after a dropped connection isn't stored twice. The type is
     * detected from the bytes and location metadata is stripped (see {@link ImageSanitizer}).
     */
    @PostMapping(path = "/houses/{houseId}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, UUID> upload(@PathVariable UUID houseId,
                                    @RequestParam(required = false) UUID id,
                                    @RequestParam("file") MultipartFile file) throws IOException {
        return Map.of("id", service.upload(houseId, id, file.getBytes()));
    }

    @GetMapping("/photos/{id}")
    public ResponseEntity<byte[]> get(@PathVariable UUID id) {
        var photo = service.getLive(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .body(photo.getData());
    }

    @DeleteMapping("/photos/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
