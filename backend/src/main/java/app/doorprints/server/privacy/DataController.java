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

package app.doorprints.server.privacy;

import app.doorprints.server.backup.BackupData;
import app.doorprints.server.backup.BackupService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * {@code GET /api/export}: everything the server holds as one JSON download, in the shared backup format
 * {@code doorprints-backup/1} ({@code /2} when the server holds brokers) — the same object an Android or web backup carries as {@code data.json}
 * (docs/schemas/README.md), so a server copy and a device copy are the same file. Photo bytes are not in it; they
 * come from {@code GET /api/photos/{id}} (a device backup puts them in the ZIP's {@code photos/} folder instead).
 *
 * <p>{@code DELETE /api/data}: erase everything, only with the header
 * {@code X-Confirm-Delete: DELETE-ALL-MY-DATA} (428 otherwise), so a stray request cannot wipe the account.
 */
@RestController
@RequestMapping("/api")
public class DataController {

    public static final String CONFIRM_HEADER = "X-Confirm-Delete";
    public static final String CONFIRM_VALUE = "DELETE-ALL-MY-DATA";

    private final DataService service;
    private final BackupService backups;

    public DataController(DataService service, BackupService backups) {
        this.service = service;
        this.backups = backups;
    }

    /** File name matches the device exporters: {@code Doorprints-backup-<UTC date>.zip} there, {@code .json} here. */
    @GetMapping("/export")
    public ResponseEntity<BackupData> export() {
        var name = "Doorprints-backup-" + LocalDate.now(ZoneOffset.UTC) + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(backups.export());
    }

    /**
     * Erases everything the server holds, but only when the confirmation header carries the exact phrase; otherwise
     * 428 and nothing is touched.
     */
    @DeleteMapping("/data")
    public ResponseEntity<?> deleteAll(@RequestHeader(name = CONFIRM_HEADER, required = false) String confirm) {
        if (!CONFIRM_VALUE.equals(confirm)) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_REQUIRED,
                    "Send the header " + CONFIRM_HEADER + ": " + CONFIRM_VALUE + " to delete all data");
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body(problem);
        }
        service.deleteAll();
        return ResponseEntity.noContent().build();
    }
}
