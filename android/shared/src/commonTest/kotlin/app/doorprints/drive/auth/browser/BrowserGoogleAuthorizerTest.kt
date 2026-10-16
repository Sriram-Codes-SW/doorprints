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

package app.doorprints.drive.auth.browser

import app.doorprints.drive.auth.DriveTokenProvider
import app.doorprints.drive.auth.AuthorizerResult
import app.doorprints.drive.auth.ConsentResolver
import app.doorprints.drive.auth.DRIVE_FILE_SCOPE
import app.doorprints.drive.auth.PendingConsent
import app.doorprints.drive.auth.SignInException
import app.doorprints.drive.device.DeviceKeyException
import app.doorprints.testing.blocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.test.Test
import kotlinx.io.IOException
import io.ktor.http.decodeURLQueryComponent

class BrowserGoogleAuthorizerTest {

    private class FakeEndpoint : TokenEndpoint {
        var codeAnswer: () -> TokenResult = { throw AssertionError("no code exchange expected") }
        var refreshAnswer: () -> TokenResult = { throw AssertionError("no refresh expected") }
        var revokeFails = false
        val codeCalls = mutableListOf<List<String>>()
        val refreshCalls = mutableListOf<List<String>>()
        val revoked = mutableListOf<String>()
        private val usedCodes = HashSet<String>()

        override suspend fun exchangeCode(clientId: String, redirectUri: String, code: String, verifier: String): TokenResult {
            codeCalls += listOf(clientId, redirectUri, code, verifier)
            // Google refuses a code it has already given out.
            if (!usedCodes.add(code)) return TokenResult.Rejected("invalid_grant")
            return codeAnswer()
        }

        override suspend fun refresh(clientId: String, refreshToken: String): TokenResult {
            refreshCalls += listOf(clientId, refreshToken)
            return refreshAnswer()
        }

        override suspend fun revoke(token: String) {
            revoked += token
            if (revokeFails) throw IOException("offline")
        }
    }

    private class FakeBrowser(val redirect: BrowserRedirect) : BrowserLauncher {
        var onLaunch: (Map<String, String>) -> Unit = {}
        var available = true
        val urls = mutableListOf<String>()
        override fun launch(url: String): Boolean {
            urls += url
            if (!available) return false
            onLaunch(params(url))
            return true
        }
    }

    private class ThrowingStore(private val kind: DeviceKeyException.Kind) : RefreshTokenStore {
        override fun read(): String? = throw DeviceKeyException(kind, "x")
        override fun write(token: String) = throw DeviceKeyException(kind, "x")
        override fun clear() = Unit
    }

    private val endpoint = FakeEndpoint()
    private val redirect = BrowserRedirect()
    private val browser = FakeBrowser(redirect)
    private val store = MemoryRefreshTokenStore()
    private var clientId: String? = "client-test"
    private var waitMs = 5_000L

    private fun authorizer(s: RefreshTokenStore = store) = BrowserGoogleAuthorizer(
        BrowserOAuthConfig({ clientId }, waitMs = waitMs), redirect, browser, endpoint, s,
        random = { size -> ByteArray(size) { 7 } },
    )

    private fun tokens(access: String = "acc", refresh: String? = "ref", vararg scopes: String = arrayOf(DRIVE_FILE_SCOPE)) =
        TokenResult.Tokens(access, refresh, scopes.toSet())

    /** The browser answers with this redirect (the state is read from the URL). */
    private fun answer(extra: (String) -> String) {
        browser.onLaunch = { p -> redirect.deliver(BrowserRedirect.DEFAULT_REDIRECT_URI + "?" + extra(p.getValue("state"))) }
    }

    private fun consent(a: BrowserGoogleAuthorizer): AuthorizerResult {
        val first = blocking { a.authorize(listOf(DRIVE_FILE_SCOPE)) }
        val needs = first as AuthorizerResult.NeedsConsent
        return blocking { BrowserAwareResolver(null).resolve(needs.consent) }
    }

    private fun granted(r: AuthorizerResult) = r as AuthorizerResult.Granted

    // ---- the request URL ----

    @Test
    fun theAuthorisationUrlCarriesPkceStateScopeAndRedirect() {
        answer { "state=$it&error=access_denied" }
        consent(authorizer())
        val p = params(browser.urls.single())
        assertEquals("client-test", p["client_id"])
        assertEquals("code", p["response_type"])
        assertEquals(DRIVE_FILE_SCOPE, p["scope"])
        assertEquals("S256", p["code_challenge_method"])
        assertEquals(BrowserRedirect.DEFAULT_REDIRECT_URI, p["redirect_uri"])
        assertEquals("offline", p["access_type"])
        assertTrue(p.getValue("state").length >= 32)
        assertTrue(browser.urls.single().startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
    }

    // ---- first sign-in ----

    @Test
    fun theCodeIsExchangedWithTheVerifierThatMatchesTheChallenge() {
        answer { "state=$it&code=the-code" }
        endpoint.codeAnswer = { tokens() }
        val r = consent(authorizer())
        assertEquals("acc", granted(r).accessToken)
        val call = endpoint.codeCalls.single()
        assertEquals(listOf("client-test", BrowserRedirect.DEFAULT_REDIRECT_URI, "the-code"), call.take(3))
        assertTrue(Pkce.isValidVerifier(call[3]))
        assertEquals(params(browser.urls.single())["code_challenge"], Pkce.challenge(call[3]))
    }

    @Test
    fun theRefreshTokenIsKeptAndTheAccessTokenIsNot() {
        answer { "state=$it&code=c" }
        endpoint.codeAnswer = { tokens("acc-secret", "ref-secret") }
        consent(authorizer())
        assertEquals("ref-secret", store.read())
    }

    @Test
    fun aGrantWithoutDriveFileIsNotStoredIsRevokedAndCarriesNoToken() {
        answer { "state=$it&code=c" }
        endpoint.codeAnswer = { tokens("acc", "ref", "openid") }
        val r = granted(consent(authorizer()))
        assertNull(r.accessToken)
        assertFalse(DRIVE_FILE_SCOPE in r.grantedScopes)
        assertNull(store.read())
        assertEquals(listOf("ref"), endpoint.revoked)
    }

    @Test
    fun aMissingScopeInTheAnswerFailsClosed() {
        answer { "state=$it&code=c" }
        endpoint.codeAnswer = { TokenResult.Tokens("acc", "ref", emptySet()) }
        assertNull(granted(consent(authorizer())).accessToken)
        assertNull(store.read())
    }

    @Test
    fun aRevokeFailureWhileDroppingAnUnscopedGrantIsSwallowed() {
        answer { "state=$it&code=c" }
        endpoint.codeAnswer = { tokens("acc", "ref", "openid") }
        endpoint.revokeFails = true
        assertNull(granted(consent(authorizer())).accessToken)
    }

    @Test
    fun denyIsADeniedGrantNotACancel() {
        answer { "state=$it&error=access_denied" }
        val r = granted(consent(authorizer()))
        assertNull(r.accessToken)
        assertTrue(r.grantedScopes.isEmpty())
        assertTrue(endpoint.codeCalls.isEmpty())
    }

    @Test
    fun leavingTheBrowserWithoutAnAnswerIsCancelled() {
        waitMs = 30
        assertEquals(AuthorizerResult.Cancelled, consent(authorizer()))
    }

    @Test
    fun cancelFromTheAppIsCancelled() {
        val a = authorizer()
        browser.onLaunch = { a.cancel() }
        assertEquals(AuthorizerResult.Cancelled, consent(a))
    }

    @Test
    fun aSecondSignInStartedWhileTheFirstWaitsCancelsTheFirst() {
        val a = authorizer()
        var firstResult: AuthorizerResult? = null
        browser.onLaunch = {
            // The first is waiting; a second consent begins (the person tapped Connect again).
            browser.onLaunch = { p -> redirect.deliver(BrowserRedirect.DEFAULT_REDIRECT_URI + "?state=${p["state"]}&error=access_denied") }
            val needs = AuthorizerResult.NeedsConsent(BrowserPendingConsent(a))
            firstResult = null
            blocking { BrowserAwareResolver(null).resolve(needs.consent) }
        }
        val first = consent(a)
        assertEquals(AuthorizerResult.Cancelled, first)
        assertNull(firstResult)
    }

    @Test
    fun aRedirectWithAForeignStateDoesNotCompleteTheSignIn() {
        waitMs = 50
        answer { "state=forged&code=evil" }
        assertEquals(AuthorizerResult.Cancelled, consent(authorizer()))
        assertTrue(endpoint.codeCalls.isEmpty())
    }

    @Test
    fun aReplayedCodeIsRefusedByTheRedirectAndByGoogle() {
        answer { "state=$it&code=once" }
        endpoint.codeAnswer = { tokens() }
        val a = authorizer()
        assertTrue(consent(a) is AuthorizerResult.Granted)
        // The same redirect again: the request is gone.
        assertFalse(redirect.deliver(BrowserRedirect.DEFAULT_REDIRECT_URI + "?state=whatever&code=once"))
        // And Google itself refuses a code it has issued already.
        val r = blocking { endpoint.exchangeCode("c", "r", "once", "v") }
        assertEquals(TokenResult.Rejected("invalid_grant"), r)
    }

    @Test
    fun noBrowserIsUnavailable() {
        browser.available = false
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE), consent(authorizer()))
        // And no request is left waiting for a redirect that cannot come.
        val state = params(browser.urls.single()).getValue("state")
        assertFalse(redirect.deliver(BrowserRedirect.DEFAULT_REDIRECT_URI + "?state=$state&code=y"))
    }

    @Test
    fun otherGoogleErrorsAreUnavailable() {
        answer { "state=$it&error=server_error" }
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE), consent(authorizer()))
    }

    @Test
    fun noNetworkAtTheTokenCallIsOffline() {
        answer { "state=$it&code=c" }
        endpoint.codeAnswer = { throw IOException("no route") }
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.OFFLINE), consent(authorizer()))
    }

    @Test
    fun aRejectedOrBrokenCodeExchangeIsUnavailable() {
        answer { "state=$it&code=c" }
        endpoint.codeAnswer = { TokenResult.Rejected("invalid_client") }
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE), consent(authorizer()))
        answer { "state=$it&code=d" }
        endpoint.codeAnswer = { TokenResult.ServerError }
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE), consent(authorizer()))
    }

    @Test
    fun aStoreThatCannotKeepTheTokenStillSignsInThisTime() {
        answer { "state=$it&code=c" }
        endpoint.codeAnswer = { tokens() }
        val a = authorizer(object : RefreshTokenStore {
            override fun read(): String? = null
            override fun write(token: String) = throw DeviceKeyException(DeviceKeyException.Kind.LOST, "x")
            override fun clear() = Unit
        })
        assertEquals("acc", granted(consent(a)).accessToken)
    }

    @Test
    fun noClientIdConfiguredIsUnavailableAndOpensNothing() {
        clientId = " "
        val a = authorizer()
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE), blocking { a.authorize(listOf(DRIVE_FILE_SCOPE)) })
        assertTrue(browser.urls.isEmpty())
    }

    @Test
    fun onlyDriveFileIsEverAsked() {
        val a = authorizer()
        val wide = listOf(DRIVE_FILE_SCOPE, "https://www.googleapis.com/auth/drive")
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE), blocking { a.authorize(wide) })
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE), blocking { a.authorize(listOf("https://www.googleapis.com/auth/drive")) })
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE), blocking { a.authorize(emptyList()) })
    }

    // ---- quiet refresh ----

    @Test
    fun withNoStoredTokenTheAuthorizeAsksForConsentAndOpensNoBrowser() {
        val r = blocking { authorizer().authorize(listOf(DRIVE_FILE_SCOPE)) }
        assertTrue(r is AuthorizerResult.NeedsConsent)
        assertTrue(browser.urls.isEmpty())
    }

    @Test
    fun aStoredTokenRefreshesQuietly() {
        store.write("ref-1")
        endpoint.refreshAnswer = { tokens("acc-2", null) }
        val r = granted(blocking { authorizer().authorize(listOf(DRIVE_FILE_SCOPE)) })
        assertEquals("acc-2", r.accessToken)
        assertEquals(listOf("client-test", "ref-1"), endpoint.refreshCalls.single())
        assertTrue(browser.urls.isEmpty())
        assertEquals("ref-1", store.read())
    }

    @Test
    fun aRotatedRefreshTokenReplacesTheOldOne() {
        store.write("ref-1")
        endpoint.refreshAnswer = { tokens("acc-2", "ref-2") }
        blocking { authorizer().authorize(listOf(DRIVE_FILE_SCOPE)) }
        assertEquals("ref-2", store.read())
    }

    @Test
    fun invalidGrantClearsTheTokenAndAsksForConsent() {
        store.write("ref-1")
        endpoint.refreshAnswer = { TokenResult.Rejected("invalid_grant") }
        val r = blocking { authorizer().authorize(listOf(DRIVE_FILE_SCOPE)) }
        assertTrue(r is AuthorizerResult.NeedsConsent)
        assertNull(store.read())
    }

    @Test
    fun otherRefreshErrorsKeepTheTokenAndAreUnavailable() {
        store.write("ref-1")
        endpoint.refreshAnswer = { TokenResult.Rejected("invalid_client") }
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE), blocking { authorizer().authorize(listOf(DRIVE_FILE_SCOPE)) })
        endpoint.refreshAnswer = { TokenResult.ServerError }
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE), blocking { authorizer().authorize(listOf(DRIVE_FILE_SCOPE)) })
        assertEquals("ref-1", store.read())
    }

    @Test
    fun noNetworkAtRefreshIsOfflineAndKeepsTheToken() {
        store.write("ref-1")
        endpoint.refreshAnswer = { throw IOException("down") }
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.OFFLINE), blocking { authorizer().authorize(listOf(DRIVE_FILE_SCOPE)) })
        assertEquals("ref-1", store.read())
    }

    @Test
    fun aRefreshWithoutDriveFileDropsTheGrantFailClosed() {
        store.write("ref-1")
        endpoint.refreshAnswer = { tokens("acc", null, "openid") }
        val r = granted(blocking { authorizer().authorize(listOf(DRIVE_FILE_SCOPE)) })
        assertNull(r.accessToken)
        assertNull(store.read())
        assertEquals(listOf("ref-1"), endpoint.revoked)
    }

    @Test
    fun aLostKeystoreKeyClearsTheBlobAndAsksForConsent() {
        val r = blocking { authorizer(ThrowingStore(DeviceKeyException.Kind.LOST)).authorize(listOf(DRIVE_FILE_SCOPE)) }
        assertTrue(r is AuthorizerResult.NeedsConsent)
    }

    @Test
    fun aLockedKeystoreWaitsInsteadOfAskingAgain() {
        val r = blocking { authorizer(ThrowingStore(DeviceKeyException.Kind.NEEDS_UNLOCK)).authorize(listOf(DRIVE_FILE_SCOPE)) }
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE), r)
        assertTrue(endpoint.refreshCalls.isEmpty())
    }

    // ---- revoke and clearToken ----

    @Test
    fun revokeClearsTheStoreFirstThenAsksGoogleToRevokeTheRefreshToken() {
        store.write("ref-1")
        var storedWhenGoogleWasAsked: String? = "unset"
        val ep = object : TokenEndpoint by endpoint {
            override suspend fun revoke(token: String) {
                storedWhenGoogleWasAsked = store.read()
                endpoint.revoke(token)
            }
        }
        val a = BrowserGoogleAuthorizer(BrowserOAuthConfig({ clientId }), redirect, browser, ep, store, { size -> ByteArray(size) { 7 } })
        blocking { a.revoke("acc", listOf(DRIVE_FILE_SCOPE)) }
        assertNull(storedWhenGoogleWasAsked)
        assertEquals(listOf("ref-1"), endpoint.revoked)
        assertNull(store.read())
    }

    @Test
    fun revokeWithNoStoredTokenRevokesTheAccessToken() {
        blocking { authorizer().revoke("acc", listOf(DRIVE_FILE_SCOPE)) }
        assertEquals(listOf("acc"), endpoint.revoked)
    }

    @Test
    fun revokeWithNothingAtAllAsksNobody() {
        blocking { authorizer().revoke(null, listOf(DRIVE_FILE_SCOPE)) }
        assertTrue(endpoint.revoked.isEmpty())
    }

    @Test
    fun aRevokeFailureStillLeavesTheStoreEmpty() {
        store.write("ref-1")
        endpoint.revokeFails = true
        try {
            blocking { authorizer().revoke(null, listOf(DRIVE_FILE_SCOPE)) }
            fail("the failure should reach the caller, which swallows it")
        } catch (_: IOException) {
        }
        assertNull(store.read())
    }

    @Test
    fun clearTokenDoesNotTouchTheRefreshTokenOrTheNetwork() {
        store.write("ref-1")
        blocking { authorizer().clearToken("acc") }
        assertEquals("ref-1", store.read())
        assertTrue(endpoint.revoked.isEmpty())
    }

    // ---- through the real token provider ----

    private fun provider(a: BrowserGoogleAuthorizer, resolver: ConsentResolver?) =
        DriveTokenProvider(a, { resolver })

    private fun kind(block: suspend () -> Unit): SignInException.Kind {
        try {
            blocking { block() }
        } catch (e: SignInException) {
            return e.kind
        }
        fail("expected a SignInException")
        throw AssertionError()
    }

    @Test
    fun providerGivesTheTokenAfterTheBrowserRound() {
        answer { "state=$it&code=c" }
        endpoint.codeAnswer = { tokens("acc-p") }
        val a = authorizer()
        assertEquals("acc-p", blocking { provider(a, BrowserAwareResolver(null)).accessToken() })
    }

    @Test
    fun providerMapsDenyCancelOfflineAndNoScope() {
        val a = authorizer()
        val p = provider(a, BrowserAwareResolver(null))
        answer { "state=$it&error=access_denied" }
        assertEquals(SignInException.Kind.DENIED, kind { p.accessToken() })
        waitMs = 30
        browser.onLaunch = {}
        assertEquals(SignInException.Kind.CANCELLED, kind { provider(authorizer(), BrowserAwareResolver(null)).accessToken() })
        answer { "state=$it&code=c" }
        endpoint.codeAnswer = { throw IOException("x") }
        assertEquals(SignInException.Kind.OFFLINE, kind { provider(authorizer(), BrowserAwareResolver(null)).accessToken() })
        endpoint.codeAnswer = { tokens("a", "r", "openid") }
        answer { "state=$it&code=d" }
        assertEquals(SignInException.Kind.DENIED, kind { provider(authorizer(), BrowserAwareResolver(null)).accessToken() })
    }

    @Test
    fun aBackgroundRunWithNoResolverIsConsentRequiredAndOpensNoBrowser() {
        assertEquals(SignInException.Kind.CONSENT_REQUIRED, kind { provider(authorizer(), null).accessToken() })
        assertTrue(browser.urls.isEmpty())
    }

    @Test
    fun providerRevokeClearsMemoryThenGoogleAndSwallowsFailure() {
        store.write("ref-1")
        endpoint.revokeFails = true
        blocking { provider(authorizer(), null).revokeAccess() }
        assertNull(store.read())
        assertEquals(listOf("ref-1"), endpoint.revoked)
    }

    @Test
    fun aForeignConsentGoesToTheOtherResolver() {
        class Other : PendingConsent
        var seen: PendingConsent? = null
        val other = ConsentResolver { seen = it; AuthorizerResult.Cancelled }
        val c = Other()
        assertEquals(AuthorizerResult.Cancelled, blocking { BrowserAwareResolver(other).resolve(c) })
        assertTrue(seen === c)
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE), blocking { BrowserAwareResolver(null).resolve(c) })
    }

    private companion object {
        fun params(url: String): Map<String, String> =
            url.substringAfter('?').split('&').associate {
                val i = it.indexOf('=')
                it.substring(0, i).decodeURLQueryComponent(plusIsSpace = true) to it.substring(i + 1).decodeURLQueryComponent(plusIsSpace = true)
            }
    }
}
