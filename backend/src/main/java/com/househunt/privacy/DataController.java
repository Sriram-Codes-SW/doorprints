package com.househunt.privacy;

import com.househunt.backup.BackupData;
import com.househunt.backup.BackupService;
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
 * {@code doorprints-backup/1} — the same object an Android or web backup carries as {@code data.json}
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
