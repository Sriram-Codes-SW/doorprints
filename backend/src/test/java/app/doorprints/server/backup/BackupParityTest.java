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

import app.doorprints.server.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copies of the {@code doorprints-backup/1} contract that live outside the server's own classes, checked from
 * the one workflow that runs when {@code docs/schemas/} changes (docs/schemas/README.md sections 7 and 8).
 *
 * <p><b>The {@code data.json} size cap is one number, 16 MiB</b>, everywhere a backup is read. It used to be four:
 * 64 MiB in {@link BackupFormat} and in the README, 16 MiB in {@code :shared}, 64 MiB in the web mirror and an
 * effective 8 MiB on the server (docs/10 section 11.3 row 7), so a backup one reader accepted could be refused by
 * another. The server constant, its configuration defaults and both client constants are pinned here; the client
 * ones are read as source text, because a backend test cannot load Kotlin or TypeScript and a comparison against
 * a number hard-coded here would only move the drift.
 *
 * <p><b>The web golden is byte-identical to the canonical sample.</b> {@code web/src/app/export/golden/backup.golden.ts}
 * is a checked-in copy of {@code docs/schemas/backup-sample.json} that the web exporter tests compare against byte
 * for byte; its own comment says nothing checks the copy against the original. This does: a change to the sample
 * alone turns this workflow red instead of leaving the web suite green against a stale copy. (Android reads the
 * sample itself, in {@code android/app/src/test/.../CanonicalSampleTest.kt}, so it has no copy to check.)
 *
 * <p>The client files are found by walking up from the working directory, like {@link CanonicalSample}. If a
 * client team moves or renames what is read here, the test fails with the path and the pattern it looked for;
 * update this class in the same change.
 */
class BackupParityTest {

    private static final long SIXTEEN_MIB = 16L * 1024 * 1024;

    @Test
    void theServerConstantIsSixteenMebibytes() {
        assertThat(BackupFormat.MAX_DATA_JSON_BYTES).isEqualTo(SIXTEEN_MIB);
    }

    /** With nothing configured, {@code POST /api/import} accepts exactly what a device reader accepts. */
    @Test
    void theImportBodyCapDefaultsToTheDataJsonCap() {
        var limits = new AppProperties.Limits(null, null, null, null, null);
        assertThat((long) limits.maxImportBytes()).isEqualTo(BackupFormat.MAX_DATA_JSON_BYTES);
    }

    /** The shipped configuration and the compose file must not undercut (or exceed) the code default. */
    @Test
    void theShippedConfigurationUsesTheSameNumber() {
        assertThat(number(classpath("/application.yml"),
                "max-import-bytes:\\s*\\$\\{MAX_IMPORT_BYTES:(\\d+)}", "application.yml"))
                .isEqualTo(BackupFormat.MAX_DATA_JSON_BYTES);
        assertThat(number(repoFile("docker-compose.yml"),
                "MAX_IMPORT_BYTES:\\s*\\$\\{MAX_IMPORT_BYTES:-(\\d+)}", "docker-compose.yml"))
                .isEqualTo(BackupFormat.MAX_DATA_JSON_BYTES);
    }

    /** {@code :shared} (Android) and the web mirror carry the same value as the server. */
    @Test
    void theClientReadersUseTheSameNumber() {
        var kotlin = "android/shared/src/commonMain/kotlin/app/doorprints/shared/export/Backup.kt";
        assertThat(product(repoFile(kotlin), "MAX_DATA_JSON_BYTES\\s*=\\s*(\\d[0-9_L \\t*]*)", kotlin))
                .as(kotlin).isEqualTo(BackupFormat.MAX_DATA_JSON_BYTES);
        var typescript = "web/src/app/export/backup-export.ts";
        assertThat(product(repoFile(typescript), "maxDataJsonBytes\\s*:\\s*(\\d[0-9_ \\t*]*)", typescript))
                .as(typescript).isEqualTo(BackupFormat.MAX_DATA_JSON_BYTES);
    }

    /** The web writer's byte golden is the canonical sample, byte for byte (docs/schemas/README.md section 8.1). */
    @Test
    void theWebGoldenIsTheCanonicalSample() {
        var path = "web/src/app/export/golden/backup.golden.ts";
        var source = repoFile(path);
        var start = "GOLDEN_BACKUP_DATA_JSON = `";
        int from = source.indexOf(start);
        assertThat(from).as("%s: no %s", path, start).isNotNegative();
        from += start.length();
        int to = source.indexOf("`;", from);
        assertThat(to).as("%s: unterminated template literal", path).isGreaterThan(from);
        assertThat(templateLiteral(source.substring(from, to), path)).isEqualTo(CanonicalSample.json());
    }

    /**
     * Completeness (readiness review 2026-09-29, docs/14 §8 finding 4): the sample's fullest house, visit, photo and broker carry
     * exactly the record components of {@link BackupHouse}, {@link BackupVisit} and {@link BackupPhoto} and {@link BackupBroker}, in order, so a
     * new field (Sprint 4c) lands in the sample and the three stacks together (Android {@code BackupFieldsTest}, web
     * {@code backup-fields.spec.ts}).
     */
    @Test
    void theSampleRecordsHaveExactlyTheRecordsComponents() {
        var root = tools.jackson.databind.json.JsonMapper.builder().build().readTree(CanonicalSample.json());
        assertThat(keysOf(root, "houses")).isEqualTo(components(BackupHouse.class));
        // Slice 1c: the rooms nested in the fullest house carry exactly the components of HouseRoom, in order.
        var rooms = new java.util.ArrayList<String>();
        for (var house : root.get("houses")) {
            if (house.has("rooms")) house.get("rooms").forEach(room -> {
                var keys = new java.util.ArrayList<String>(room.propertyNames());
                if (keys.size() > rooms.size()) { rooms.clear(); rooms.addAll(keys); }
            });
        }
        assertThat(rooms).isEqualTo(components(app.doorprints.server.house.HouseRoom.class));
        // Slice 3a: the fullest answer nested in the houses carries exactly the components of HouseAnswer, in order.
        var answers = new java.util.ArrayList<String>();
        for (var house : root.get("houses")) {
            if (house.has("answers")) house.get("answers").forEach(answer -> {
                var keys = new java.util.ArrayList<String>(answer.propertyNames());
                if (keys.size() > answers.size()) { answers.clear(); answers.addAll(keys); }
            });
        }
        assertThat(answers).isEqualTo(components(app.doorprints.server.house.HouseAnswer.class));
        // Slice 5: the fullest move-in carries exactly the components of HouseMoveIn, and the union of the items' keys
        // (done only when true) exactly those of its Item, in order.
        var moveIn = new java.util.ArrayList<String>();
        var itemKeys = new java.util.LinkedHashSet<String>();
        for (var house : root.get("houses")) {
            if (house.has("moveIn")) {
                var keys = new java.util.ArrayList<String>(house.get("moveIn").propertyNames());
                if (keys.size() > moveIn.size()) { moveIn.clear(); moveIn.addAll(keys); }
                house.get("moveIn").get("items").forEach(item -> item.propertyNames().forEach(itemKeys::add));
            }
        }
        assertThat(moveIn).isEqualTo(components(app.doorprints.server.house.HouseMoveIn.class));
        assertThat(components(app.doorprints.server.house.HouseMoveIn.Item.class).stream().filter(itemKeys::contains).toList())
                .isEqualTo(components(app.doorprints.server.house.HouseMoveIn.Item.class));
        assertThat(keysOf(root, "visits")).isEqualTo(components(BackupVisit.class));
        assertThat(keysOf(root, "photos")).isEqualTo(components(BackupPhoto.class));
        assertThat(keysOf(root, "brokers")).isEqualTo(components(BackupBroker.class));
        // Criteria and preferences spread optional fields across multiple rows, so compare union with component order preserved
        assertThat(unionOf(root, "criteria")).isEqualTo(components(BackupCriterion.class));
        assertThat(unionOf(root, "preferences")).isEqualTo(components(BackupPreference.class));
        // Slice 3a: no single question has every key (only an archived one has archived), so the union again.
        assertThat(unionOf(root, "questions")).isEqualTo(components(BackupQuestion.class));
        // Slice 3b-1: huntReminder, withWhom, notes and visitId are optional, so the union over the rows again.
        assertThat(unionOf(root, "viewings")).isEqualTo(components(BackupViewing.class));
        // Slice 4a: enabled (only false), areaId and street (one per note) are optional, so the union again.
        assertThat(unionOf(root, "areas")).isEqualTo(components(BackupArea.class));
        assertThat(unionOf(root, "places")).isEqualTo(components(BackupPlace.class));
        assertThat(unionOf(root, "areaNotes")).isEqualTo(components(BackupAreaNote.class));
        // The list order of data.json is part of the format too (README section 3): criteria, preferences and questions come after brokers.
        assertThat(new java.util.ArrayList<>(root.propertyNames()))
                .isEqualTo(components(BackupData.class));
    }

    private static java.util.List<String> keysOf(tools.jackson.databind.JsonNode root, String list) {
        // The fullest row: a list may lead with a row that leaves its optional fields out (the sample's first broker).
        java.util.List<String> fullest = java.util.List.of();
        for (var row : root.get(list)) {
            var keys = new java.util.ArrayList<String>(row.propertyNames());
            if (keys.size() > fullest.size()) fullest = keys;
        }
        return fullest;
    }

    private static java.util.List<String> unionOf(tools.jackson.databind.JsonNode root, String list) {
        // Union of all keys in the list, preserving component order (not row order)
        var recordClass = switch (list) {
            case "criteria" -> BackupCriterion.class;
            case "preferences" -> BackupPreference.class;
            case "questions" -> BackupQuestion.class;
            case "viewings" -> BackupViewing.class;
            case "areas" -> BackupArea.class;
            case "places" -> BackupPlace.class;
            case "areaNotes" -> BackupAreaNote.class;
            default -> throw new IllegalArgumentException("Unknown list: " + list);
        };
        var componentOrder = components(recordClass);
        var keys = new java.util.LinkedHashSet<String>();
        for (var row : root.get(list)) {
            for (var key : row.propertyNames()) {
                keys.add(key);
            }
        }
        // Return in the component's declared order
        return componentOrder.stream().filter(keys::contains).toList();
    }

    private static java.util.List<String> components(Class<? extends Record> record) {
        return java.util.Arrays.stream(record.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** The first capture group of {@code regex} in {@code text}, as a number. */
    private static long number(String text, String regex, String where) {
        var matcher = Pattern.compile(regex).matcher(text);
        assertThat(matcher.find()).as("%s: no match for %s", where, regex).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    /**
     * The first capture group of {@code regex} in {@code text}, read as a product of integer literals such as
     * {@code 16L * 1024 * 1024} or {@code 16 * 1024 * 1024} (underscores and a Kotlin {@code L} suffix allowed).
     */
    private static long product(String text, String regex, String where) {
        var matcher = Pattern.compile(regex).matcher(text);
        assertThat(matcher.find()).as("%s: no match for %s", where, regex).isTrue();
        long result = 1;
        for (var factor : matcher.group(1).split("\\*")) {
            var digits = factor.strip().replace("_", "").replace("L", "");
            assertThat(digits).as("%s: not a product of integer literals: %s", where, matcher.group(1))
                    .matches("\\d+");
            result = Math.multiplyExact(result, Long.parseLong(digits));
        }
        return result;
    }

    /**
     * The text of a TypeScript template literal body. Only the escapes a JSON document can need are expected — a
     * backslash followed by a backslash, a backtick or a dollar sign; any other escape, or an interpolation, fails
     * the test rather than being guessed at.
     */
    private static String templateLiteral(String body, String where) {
        assertThat(body).as("%s: the golden must not interpolate", where).doesNotContain("${");
        var out = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            assertThat(i + 1).as("%s: trailing backslash", where).isLessThan(body.length());
            char next = body.charAt(++i);
            assertThat("\\`$".indexOf(next)).as("%s: unexpected escape \\%s", where, next).isNotNegative();
            out.append(next);
        }
        return out.toString();
    }

    private static String classpath(String resource) {
        try (InputStream in = BackupParityTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("classpath resource %s", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Walks up from the working directory (Surefire starts in {@code backend/}) to the repository root. */
    private static String repoFile(String relative) {
        var dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParent()) {
            var candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException(relative + " not found above " + Path.of("").toAbsolutePath());
    }
}
