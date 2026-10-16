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

package app.doorprints.drive.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The decisions of the Android Drive token provider over a fake Google (docs/15 §5.5; twin of google-token-provider.spec.ts). */
class DriveTokenProviderTest {

    private class FakeConsent : PendingConsent

    private class FakeAuthorizer : GoogleAuthorizer {
        val answers = ArrayDeque<AuthorizerResult>()
        val asked = mutableListOf<List<String>>()
        val cleared = mutableListOf<String>()
        val revoked = mutableListOf<Pair<String?, List<String>>>()
        var revokeFails = false

        override suspend fun authorize(scopes: List<String>): AuthorizerResult {
            asked += scopes
            // A real Google call suspends; yielding lets two callers interleave when nothing holds them back.
            yield()
            return answers.removeFirst()
        }

        override suspend fun clearToken(accessToken: String) {
            cleared += accessToken
        }

        override suspend fun revoke(accessToken: String?, scopes: List<String>) {
            revoked += accessToken to scopes
            if (revokeFails) throw IllegalStateException("network")
        }
    }

    private var now = 1_000_000L
    private val google = FakeAuthorizer()
    private var resolver: ConsentResolver? = null

    private fun provider() = DriveTokenProvider(google, { resolver }, { now })

    private fun granted(token: String? = "tok-1", vararg scopes: String = arrayOf(DRIVE_FILE_SCOPE)) =
        AuthorizerResult.Granted(token, scopes.toSet())

    private fun failure(block: suspend () -> Unit): SignInException {
        try {
            runBlocking { block() }
        } catch (e: SignInException) {
            return e
        }
        fail("expected a SignInException")
        throw AssertionError()
    }

    @Test
    fun grantedWithDriveFileGivesTheToken() = runBlocking {
        google.answers += granted("tok-1")
        assertEquals("tok-1", provider().accessToken())
    }

    @Test
    fun asksForExactlyTheDriveFileScope() = runBlocking {
        google.answers += granted()
        provider().accessToken()
        assertEquals(listOf(listOf("https://www.googleapis.com/auth/drive.file")), google.asked)
    }

    @Test
    fun grantWithoutDriveFileIsDenied() {
        // Granular consent: the person unticked the Drive box, Google still returns a token for the rest.
        google.answers += granted("tok-1", "email")
        assertEquals(SignInException.Kind.DENIED, failure { provider().accessToken() }.kind)
    }

    @Test
    fun grantWithNoScopesIsDenied() {
        google.answers += AuthorizerResult.Granted("tok-1", emptySet())
        assertEquals(SignInException.Kind.DENIED, failure { provider().accessToken() }.kind)
    }

    @Test
    fun extraScopesDoNotMatterWhenDriveFileIsThere() = runBlocking {
        google.answers += granted("tok-1", "email", DRIVE_FILE_SCOPE)
        assertEquals("tok-1", provider().accessToken())
    }

    @Test
    fun aScopeThatMerelyContainsDriveFileIsNotDriveFile() {
        google.answers += granted("tok-1", "https://www.googleapis.com/auth/drive.file.readonly", "https://www.googleapis.com/auth/drive")
        assertEquals(SignInException.Kind.DENIED, failure { provider().accessToken() }.kind)
    }

    @Test
    fun noTokenIsDeniedEvenWithTheScope() {
        google.answers += granted(null)
        assertEquals(SignInException.Kind.DENIED, failure { provider().accessToken() }.kind)
    }

    @Test
    fun blankTokenIsDenied() {
        google.answers += granted("")
        assertEquals(SignInException.Kind.DENIED, failure { provider().accessToken() }.kind)
    }

    @Test
    fun aDeniedAnswerLeavesNothingInMemory() = runBlocking {
        val p = provider()
        google.answers += granted("tok-1", "email")
        failure { p.accessToken() }
        google.answers += granted("tok-2")
        assertEquals("tok-2", p.accessToken())
        assertEquals(2, google.asked.size)
    }

    @Test
    fun cancelledIsNotDenied() {
        google.answers += AuthorizerResult.Cancelled
        assertEquals(SignInException.Kind.CANCELLED, failure { provider().accessToken() }.kind)
    }

    @Test
    fun offlineAndUnavailableKeepTheirKind() {
        google.answers += AuthorizerResult.Failed(AuthorizerResult.FailureKind.OFFLINE)
        assertEquals(SignInException.Kind.OFFLINE, failure { provider().accessToken() }.kind)
        google.answers += AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE)
        assertEquals(SignInException.Kind.UNAVAILABLE, failure { provider().accessToken() }.kind)
    }

    @Test
    fun theTokenIsKeptInMemoryAndNotAskedAgainWhileFresh() = runBlocking {
        google.answers += granted("tok-1")
        val p = provider()
        assertEquals("tok-1", p.accessToken())
        now += 49 * 60_000
        assertEquals("tok-1", p.accessToken())
        assertEquals(1, google.asked.size)
    }

    @Test
    fun anOldTokenIsAskedForAgain() = runBlocking {
        google.answers += granted("tok-1")
        google.answers += granted("tok-2")
        val p = provider()
        p.accessToken()
        now += 51 * 60_000
        assertEquals("tok-2", p.accessToken())
    }

    @Test
    fun aRefreshThatLosesTheDrivePermissionDeniesAndDropsTheOldToken() = runBlocking {
        google.answers += granted("tok-1")
        google.answers += granted("tok-2", "email")
        google.answers += granted("tok-3")
        val p = provider()
        p.accessToken()
        now += 51 * 60_000
        assertEquals(SignInException.Kind.DENIED, failure { p.accessToken() }.kind)
        assertEquals("tok-3", p.accessToken())
    }

    @Test
    fun concurrentCallersShareOneRequest() = runBlocking {
        google.answers += granted("tok-1")
        val p = provider()
        val all = (1..8).map { async { p.accessToken() } }.awaitAll()
        assertEquals(List(8) { "tok-1" }, all)
        assertEquals(1, google.asked.size)
    }

    @Test
    fun onRejectedDropsTheTokenAndTellsGoogle() = runBlocking {
        google.answers += granted("tok-1")
        google.answers += granted("tok-2")
        val p = provider()
        p.accessToken()
        p.onRejected("tok-1")
        assertEquals(listOf("tok-1"), google.cleared)
        assertEquals("tok-2", p.accessToken())
    }

    @Test
    fun onRejectedOfAnotherTokenKeepsTheCurrentOne() = runBlocking {
        google.answers += granted("tok-1")
        val p = provider()
        p.accessToken()
        p.onRejected("old-token")
        assertEquals("tok-1", p.accessToken())
        assertEquals(1, google.asked.size)
    }

    @Test
    fun consentIsResolvedThroughTheUiAndThenChecked() = runBlocking {
        val consent = FakeConsent()
        google.answers += AuthorizerResult.NeedsConsent(consent)
        var shown: PendingConsent? = null
        resolver = ConsentResolver { c ->
            shown = c
            granted("tok-9")
        }
        assertEquals("tok-9", provider().accessToken())
        assertTrue(shown === consent)
    }

    @Test
    fun consentAnsweredWithoutDriveFileIsDenied() {
        google.answers += AuthorizerResult.NeedsConsent(FakeConsent())
        resolver = ConsentResolver { granted("tok-9", "email") }
        assertEquals(SignInException.Kind.DENIED, failure { provider().accessToken() }.kind)
    }

    @Test
    fun consentClosedIsCancelled() {
        google.answers += AuthorizerResult.NeedsConsent(FakeConsent())
        resolver = ConsentResolver { AuthorizerResult.Cancelled }
        assertEquals(SignInException.Kind.CANCELLED, failure { provider().accessToken() }.kind)
    }

    @Test
    fun consentNeededWithNobodyThereAsksToConnectAgain() {
        google.answers += AuthorizerResult.NeedsConsent(FakeConsent())
        resolver = null
        assertEquals(SignInException.Kind.CONSENT_REQUIRED, failure { provider().accessToken() }.kind)
    }

    @Test
    fun consentThatNeedsConsentAgainDoesNotLoop() {
        google.answers += AuthorizerResult.NeedsConsent(FakeConsent())
        var calls = 0
        resolver = ConsentResolver {
            calls++
            AuthorizerResult.NeedsConsent(FakeConsent())
        }
        assertEquals(SignInException.Kind.UNAVAILABLE, failure { provider().accessToken() }.kind)
        assertEquals(1, calls)
    }

    @Test
    fun revokeClearsMemoryAndAsksGoogleWithTheTokenAndScope() = runBlocking {
        google.answers += granted("tok-1")
        google.answers += granted("tok-2")
        val p = provider()
        p.accessToken()
        p.revokeAccess()
        assertEquals(listOf<Pair<String?, List<String>>>("tok-1" to listOf(DRIVE_FILE_SCOPE)), google.revoked)
        assertEquals("tok-2", p.accessToken())
        assertEquals(2, google.asked.size)
    }

    @Test
    fun revokeWithoutATokenStillAsksGoogle() = runBlocking {
        provider().revokeAccess()
        assertEquals(listOf<Pair<String?, List<String>>>(null to listOf(DRIVE_FILE_SCOPE)), google.revoked)
    }

    @Test
    fun revokeClearsMemoryEvenWhenGoogleFails() = runBlocking {
        google.answers += granted("tok-1")
        google.answers += granted("tok-2")
        val p = provider()
        p.accessToken()
        google.revokeFails = true
        p.revokeAccess()
        assertEquals("tok-2", p.accessToken())
    }

    @Test
    fun theTokenNeverAppearsInResultsOrErrors() {
        assertFalse(granted("secret-token").toString().contains("secret-token"))
        google.answers += granted("secret-token", "email")
        assertFalse(failure { provider().accessToken() }.toString().contains("secret-token"))
    }
}
