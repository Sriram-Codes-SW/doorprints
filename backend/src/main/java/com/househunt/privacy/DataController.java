package com.househunt.privacy;

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
 * {@code GET /api/export}: everything as one JSON download. {@code DELETE /api/data}: erase everything, only with the
 * header {@code X-Confirm-Delete: DELETE-ALL-MY-DATA} (428 otherwise), so a stray request cannot wipe the account.
 */
@RestController
@RequestMapping("/api")
public class DataController {

    public static final String CONFIRM_HEADER = "X-Confirm-Delete";
    public static final String CONFIRM_VALUE = "DELETE-ALL-MY-DATA";

    private final DataService service;

    public DataController(DataService service) {
        this.service = service;
    }

    @GetMapping("/export")
    public ResponseEntity<DataService.Export> export() {
        var name = "doorprints-export-" + LocalDate.now(ZoneOffset.UTC) + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(service.export());
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
