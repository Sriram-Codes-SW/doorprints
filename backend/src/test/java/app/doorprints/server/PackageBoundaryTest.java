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

package app.doorprints.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4b-BL-165: the shared code every part of the server uses ({@code config}, {@code common}) must not reach into
 * the optional AI code ({@code ai}), so the AI packages can be left out or moved without breaking the core. A plain
 * scan of the source files (no library): a line naming the AI package is a violation.
 */
class PackageBoundaryTest {

    private static final Path MAIN = Path.of("src", "main", "java", "app", "doorprints", "server");

    @Test
    void configAndCommonDoNotNameTheAiPackages() throws IOException {
        assertThat(sourcesOf("config")).isNotEmpty();
        assertThat(sourcesOf("common")).isNotEmpty();
        assertThat(violations("config", "common")).isEmpty();
    }

    @Test
    void theScanSeesAViolation() {
        assertThat(isViolation("import app.doorprints.server.ai.web.TokenBucketRateLimiter;")).isTrue();
        assertThat(isViolation("var x = new app.doorprints.server.ai.config.AiProperties();")).isTrue();
        assertThat(isViolation("import app.doorprints.server.common.TokenBucketRateLimiter;")).isFalse();
        assertThat(isViolation("// an aide, not the ai package")).isFalse();
    }

    static boolean isViolation(String line) {
        return line.contains("app.doorprints.server.ai.");
    }

    private static List<Path> sourcesOf(String pkg) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN.resolve(pkg))) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }

    private static List<String> violations(String... packages) throws IOException {
        var found = new ArrayList<String>();
        for (var pkg : packages) {
            for (var file : sourcesOf(pkg)) {
                var lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (isViolation(lines.get(i))) found.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }
        return found;
    }
}
