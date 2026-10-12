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

package app.doorprints.server.backup;

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
 * in {@code android/app/src/test/java/app/doorprints/CanonicalSampleTest.kt} (parsed-JSON, ticket S4-00/a).
 * Because only the backend workflow runs when {@code docs/schemas/} alone changes, a format change that the
 * clients have not followed fails here first (docs/schemas/README.md section 9, S4-00/d).
 */
final class CanonicalSample {

    /** Key names in the order they appear, e.g. {@code format, exportedAt, houses, id, label, ...}. */
    private static final Pattern KEY = Pattern.compile("\"([A-Za-z][A-Za-z0-9_]*)\"\\s*:");

    private CanonicalSample() {
    }

    /** The sample as written (the new family since kinds step 3: {@code candidates}), without the trailing newline. */
    static String text() {
        return read("docs/schemas/backup-sample.json").strip();
    }

    /**
     * The sample as this server's rows of the family before hold it ({@code houses}, {@code houseId}, a {@code /2} copy):
     * read through {@link BackupWire#legacyOf}, which is how {@code POST /api/import} reads it. The suites that build the
     * server's data from the sample and compare exports with it work in this shape; {@code BackupWireTest} pins that it is
     * exactly {@code backup-sample-houses.json}, the sample before the reset, and {@code BackupApiTest} that the export is
     * the new family.
     */
    static String json() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        return mapper.writeValueAsString(BackupWire.legacyOf(mapper.readTree(text())));
    }

    /** The sample of the family before, as it was before the reset ({@code docs/schemas/backup-sample-houses.json}). */
    static String houses() {
        return read("docs/schemas/backup-sample-houses.json").strip();
    }

    /**
     * Walks up from the working directory (Surefire starts in {@code backend/}) until the repository's
     * {@code docs/schemas/backup-sample.json} turns up, so the test does not care where it is started from.
     */
    private static String read(String file) {
        var dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParent()) {
            var candidate = dir.resolve(file);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException(file + " not found above "
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
