package app.doorprints.server.backup;

import app.doorprints.server.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/import}: restore a {@code doorprints-backup/1} file (its {@code data.json}) onto this server.
 *
 * <p>Protected by the API key like every other {@code /api} path (deny by default, {@code ApiKeyFilter}). Two
 * size limits guard it: the body is capped at {@code app.limits.max-import-bytes} by {@code RequestSizeLimitFilter}
 * (413), and the number of rows at {@code app.limits.max-import-rows} (413 as well). Anything else that is wrong
 * with the file is a 400 naming the rows.
 *
 * <p>{@code ?dryRun=true} is the preview of docs/11 section 5.2: the same validation and the same merge decisions,
 * nothing written. Clients should call it first and show the counts before asking the user to confirm.
 */
@RestController
@RequestMapping("/api")
public class BackupController {

    /** The one path that may carry a bigger JSON body (see {@code WebConfig}). */
    public static final String IMPORT_PATH = "/api/import";

    private final BackupService service;
    private final int maxRows;

    public BackupController(BackupService service, AppProperties props) {
        this.service = service;
        this.maxRows = props.limits().maxImportRows();
    }

    @PostMapping("/import")
    public ResponseEntity<?> importBackup(@RequestBody BackupData body,
                                          @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun) {
        if (body.rowCount() > maxRows) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                    "Backup has " + body.rowCount() + " rows (max " + maxRows + " per import)");
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(problem);
        }
        return ResponseEntity.ok(service.importBackup(body, dryRun));
    }
}
