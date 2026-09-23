package com.househunt.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the canonical backup sample of the shared format: {@code docs/schemas/backup-sample.json} (see
 * {@code docs/schemas/README.md}). On the server it is read by {@code BackupMapperTest}, {@code BackupApiTest}
 * (parsed-JSON goldens) and {@code BackupParityTest}, which checks that the web writer's byte golden
 * ({@code web/src/app/export/golden/backup.golden.ts}) is still an exact copy of it. Android reads the file itself,
 * in {@code android/app/src/test/java/com/househunt/app/CanonicalSampleTest.kt} (parsed-JSON, ticket S4-00/a).
 * Because only the backend workflow runs when {@code docs/schemas/} alone changes, a format change that the
 * clients have not followed fails here first (docs/schemas/README.md section 9, S4-00/d).
 */
final class CanonicalSample {

    /** Key names in the order they appear, e.g. {@code format, exportedAt, houses, id, label, ...}. */
    private static final Pattern KEY = Pattern.compile("\"([A-Za-z][A-Za-z0-9_]*)\"\\s*:");

    private CanonicalSample() {
    }

    /** The sample as written, without the trailing newline. */
    static String json() {
        return read().strip();
    }

    /**
     * Walks up from the working directory (Surefire starts in {@code backend/}) until the repository's
     * {@code docs/schemas/backup-sample.json} turns up, so the test does not care where it is started from.
     */
    private static String read() {
        var dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParent()) {
            var candidate = dir.resolve("docs/schemas/backup-sample.json");
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException("docs/schemas/backup-sample.json not found above "
                + Path.of("").toAbsolutePath());
    }

    /**
     * The JSON object keys of a document in the order they are written, checklist items included. Comparing two of
     * these is how the tests pin the property order without depending on Jackson's defaults.
     */
    static List<String> keysInOrder(String json) {
        var keys = new ArrayList<String>();
        var matcher = KEY.matcher(json);
        while (matcher.find()) keys.add(matcher.group(1));
        return keys;
    }
}
