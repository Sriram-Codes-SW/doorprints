package com.househunt.backup;

import com.househunt.config.AppProperties;
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
        var kotlin = "android/shared/src/commonMain/kotlin/com/househunt/shared/export/Backup.kt";
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
