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

package app.doorprints.drive

import app.doorprints.drive.backup.DriveProblem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The diagnostic code of an unexpected failure (S4b-BL-146): the step and the exception's class name, nothing else. These tests
 * are the privacy proof: no message, cause message, stack text, URL, id or content can reach the code.
 */
class DriveCodeTest {
    private val allowed = Regex("""[a-z-]{1,16}/[A-Za-z0-9_.$]{1,120}""")

    private class SecretLeak(message: String, cause: Throwable?) : RuntimeException(message, cause)

    private fun hostile() = SecretLeak(
        "token=ya29.SECRET https://www.googleapis.com/drive/v3/files/1AbCdEf user@example.org password=hunter2",
        IllegalStateException("cause-secret keys.json id=0123456789abcdef"),
    )

    @Test fun theCodeIsTheStepAndTheClassNameOnly() {
        assertEquals("join/java.lang.IllegalStateException", DriveCode.of("join", IllegalStateException("x")))
        assertEquals("backup/java.lang.NoClassDefFoundError", DriveCode.of("backup", NoClassDefFoundError("Lcom/secret/Thing;")))
        assertEquals("open/java.lang.ExceptionInInitializerError", DriveCode.of("open", ExceptionInInitializerError("init secret")))
    }

    @Test fun noMessageCauseOrStackTextReachesTheCode() {
        val code = DriveCode.of("join", hostile())
        assertTrue(allowed.matches(code), code)
        for (secret in listOf("ya29", "SECRET", "googleapis", "example.org", "hunter2", "cause-secret", "keys.json", "0123456789abcdef", "token", "http")) {
            assertFalse(secret in code, "$secret leaked into $code")
        }
        assertTrue(code.endsWith("SecretLeak"), code)
    }

    @Test fun aClassNameIsFilteredToTheAllowListAndTruncated() {
        assertEquals("Foo.Bar\$Baz_1", DriveCode.sanitizeClassName("Foo.Bar\$Baz_1"))
        assertEquals("abcsecret1httpsx", DriveCode.sanitizeClassName("a b\nc=secret/1: https://x"))
        assertEquals("Unknown", DriveCode.sanitizeClassName(null))
        assertEquals("Unknown", DriveCode.sanitizeClassName(" /:\n"))
        val long = "p." + "A".repeat(500)
        val cut = DriveCode.sanitizeClassName(long)
        assertEquals(DriveCode.MAX_CLASS_NAME, cut.length)
        assertTrue(cut.endsWith("A"), "the tail (the class itself) is kept")
    }

    @Test fun aStepOutsideTheAllowListIsReplacedNotCopied() {
        assertEquals("join/java.lang.RuntimeException", DriveCode.of("join", RuntimeException()))
        val bad = DriveCode.of("join user@example.org", RuntimeException())
        assertTrue(bad.startsWith("unknown/"), bad)
        assertTrue(allowed.matches(DriveCode.of("", RuntimeException())))
        assertEquals("join-write/java.lang.RuntimeException", DriveCode.of("join-write", RuntimeException()))
    }

    @Test fun anAnonymousClassStillGivesACode() {
        val e = object : RuntimeException("anon secret") {}
        val code = DriveCode.of("sync", e)
        assertTrue(allowed.matches(code), code)
        assertFalse("secret" in code)
    }

    @Test fun onlyAnUnexpectedExceptionGetsACodeNeverATypedOne() {
        assertNull(DriveProblem.of(DriveException(DriveException.Kind.OFFLINE), "join").code)
        assertNull(DriveProblem.of(app.doorprints.crypto.KeysException(app.doorprints.crypto.KeysException.Kind.REVOKED, "x"), "join").code)
        assertNull(DriveProblem.codeOf(app.doorprints.crypto.CryptoException(app.doorprints.crypto.CryptoException.Kind.UNAVAILABLE, "x"), "join"))
        val p = DriveProblem.of(hostile(), "join")
        assertEquals(DriveProblem.Kind.SOURCE_FAILED, p.kind)
        assertNotNull(p.code)
        assertTrue(allowed.matches(p.code!!))
        assertFalse(p.toString().contains("hunter2"))
        // Without a step the old call keeps working and still never carries a message.
        assertNull(DriveProblem.of(RuntimeException("x")).code)
    }

    @Test fun theCodeSourceNeverReadsAMessageACauseOrAStack() {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val text = File(dir, "shared/src/commonMain/kotlin/app/doorprints/drive/DriveCode.kt").readText()
            .lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/*") || it.trim().startsWith("//") }.joinToString("\n")
        for (banned in listOf("message", "cause", "stackTrace", "toString", "printStackTrace", "suppressed", "localizedMessage")) {
            assertFalse(banned in text, "DriveCode.kt must not use `$banned`")
        }
    }
}
