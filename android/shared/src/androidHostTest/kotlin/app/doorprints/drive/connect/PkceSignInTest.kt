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

package app.doorprints.drive.connect

import app.doorprints.crypto.JvmCryptoProvider
import app.doorprints.drive.DriveException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The code flow with PKCE against fakes: the browser, Google's token endpoint and the sealed store (S4b-BL-117). */
class PkceSignInTest {
    private val config = GoogleAuthConfig("test-client-id.apps.example", "app.doorprints:/oauth2redirect")
    private val drive = GoogleAuthConfig.SCOPE_DRIVE_FILE

    /** The browser: remembers the URL it was sent to and answers like Google would, or as scripted. */
    private class FakeBrowser(var answer: (String) -> BrowserResult) : AuthBrowser {
        var urls = mutableListOf<String>()
        override suspend fun authorize(url: String, redirectUri: String): BrowserResult {
            urls += url
            return answer(url)
        }
    }

    private class FakeGoogle : OAuthTransport {
        val posts = mutableListOf<Pair<String, Map<String, String>>>()
        var respond: (String, Map<String, String>) -> OAuthResponse = { _, _ -> OAuthResponse(200, "{}") }
        var boom: Exception? = null
        override suspend fun postForm(url: String, fields: Map<String, String>): OAuthResponse {
            posts += url to fields
            boom?.let { throw it }
            return respond(url, fields)
        }
    }

    private class BrokenStore : RefreshTokenStore {
        override fun load(): String? = throw TokenStoreException("sealed store gone")
        override fun save(token: String) = throw TokenStoreException("no keystore")
        override fun clear() = throw TokenStoreException("no keystore")
    }

    private var now = 1_000_000L
    private val store = MemoryRefreshTokenStore()
    private val google = FakeGoogle()

    private fun redirectWith(url: String, extra: String): BrowserResult {
        val state = Regex("state=([^&]+)").find(url)!!.groupValues[1]
        return BrowserResult.Redirected("${config.redirectUri}?state=$state&$extra")
    }

    private fun tokens(access: String = "access-1", refresh: String? = "refresh-1", scope: String = drive, expires: Int = 3600) =
        OAuthResponse(
            200,
            buildString {
                append("""{"access_token":"$access","expires_in":$expires,"scope":"$scope","token_type":"Bearer"""")
                if (refresh != null) append(""","refresh_token":"$refresh"""")
                append("}")
            },
        )

    private fun signIn(browser: AuthBrowser, st: RefreshTokenStore = store, cfg: GoogleAuthConfig = config) =
        PkceGoogleSignIn(cfg, browser, google, st, JvmCryptoProvider) { now }

    @Test
    fun rfc7636VectorForTheS256Challenge() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeOf(JvmCryptoProvider, "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun theVerifierIsLongRandomAndNeverPrinted() {
        val a = Pkce.create(JvmCryptoProvider)
        val b = Pkce.create(JvmCryptoProvider)
        assertEquals(43, a.verifier.length)
        assertTrue(a.verifier != b.verifier && a.state != b.state)
        assertTrue(a.verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertFalse(a.toString().contains(a.verifier))
    }

    @Test
    fun theConsentUrlAsksForDriveFileOnlyWithPkceAndNoSecret() {
        val pkce = Pkce.create(JvmCryptoProvider)
        val url = OAuthFlow.authorizationUrl(config, pkce)
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file&" in url + "&")
        assertTrue("code_challenge_method=S256" in url && "code_challenge=${pkce.challenge}" in url)
        assertTrue("response_type=code" in url && "access_type=offline" in url && "client_id=test-client-id.apps.example" in url)
        assertFalse("secret" in url)
        assertFalse("verifier" in url || pkce.verifier in url)
        assertEquals(1, Regex("scope=").findAll(url).count())
    }

    @Test
    fun connectStoresOnlyTheRefreshTokenAndTheAccessTokenWorksFromMemory() = runTest {
        google.respond = { _, f ->
            assertEquals("authorization_code", f["grant_type"])
            assertEquals(config.redirectUri, f["redirect_uri"]); assertNotNull(f["code_verifier"]); assertNull(f["client_secret"])
            tokens()
        }
        val browser = FakeBrowser { redirectWith(it, "code=the-code") }
        val s = signIn(browser)
        assertEquals(SignInResult.Connected, s.connect())
        assertEquals("refresh-1", store.load())
        assertTrue(s.isConnected())
        assertEquals("access-1", s.accessToken())
        assertEquals(1, google.posts.size, "the cached access token needs no second call")
        // The verifier sent is the one whose hash the browser got.
        val challenge = Regex("code_challenge=([^&]+)").find(browser.urls.single())!!.groupValues[1]
        assertEquals(challenge, Pkce.challengeOf(JvmCryptoProvider, google.posts.first().second.getValue("code_verifier")))
    }

    @Test
    fun aStateThatIsNotThisAttemptsIsRefusedAndNothingIsStoredOrPosted() = runTest {
        val s = signIn(FakeBrowser { BrowserResult.Redirected("${config.redirectUri}?state=forged&code=evil") })
        assertEquals(SignInResult.Failed(SignInFailure.STATE_MISMATCH), s.connect())
        val s2 = signIn(FakeBrowser { BrowserResult.Redirected("${config.redirectUri}?code=no-state") })
        assertEquals(SignInResult.Failed(SignInFailure.STATE_MISMATCH), s2.connect())
        val s3 = signIn(FakeBrowser { BrowserResult.Redirected("https://evil.example/cb?state=x&code=y") })
        assertEquals(SignInResult.Failed(SignInFailure.STATE_MISMATCH), s3.connect())
        assertNull(store.load()); assertTrue(google.posts.isEmpty())
    }

    @Test
    fun cancelledDeniedAndUnavailableBrowsers() = runTest {
        assertEquals(SignInResult.Cancelled, signIn(FakeBrowser { BrowserResult.Cancelled }).connect())
        assertEquals(SignInResult.Cancelled, signIn(FakeBrowser { redirectWith(it, "error=access_denied") }).connect())
        assertEquals(SignInResult.Failed(SignInFailure.DENIED), signIn(FakeBrowser { redirectWith(it, "error=server_error") }).connect())
        assertEquals(SignInResult.Failed(SignInFailure.NOT_AVAILABLE), signIn(FakeBrowser { BrowserResult.Unavailable }).connect())
        assertEquals(SignInResult.Failed(SignInFailure.NOT_AVAILABLE), signIn(FakeBrowser { throw IllegalStateException("no browser") }).connect())
        assertTrue(google.posts.isEmpty() && store.load() == null)
    }

    @Test
    fun aWiderScopeIsNeverKeptAndTheGrantIsRevoked() = runTest {
        google.respond = { url, _ -> if (url == GoogleAuthConfig.REVOKE_ENDPOINT) OAuthResponse(200, "") else tokens(scope = "$drive https://www.googleapis.com/auth/drive") }
        val s = signIn(FakeBrowser { redirectWith(it, "code=c") })
        assertEquals(SignInResult.Failed(SignInFailure.WRONG_SCOPE), s.connect())
        assertNull(store.load())
        assertEquals(GoogleAuthConfig.REVOKE_ENDPOINT, google.posts.last().first)
        assertEquals("refresh-1", google.posts.last().second["token"])
    }

    @Test
    fun noRefreshTokenOrAnErrorBodyIsABadAnswer() = runTest {
        google.respond = { _, _ -> tokens(refresh = null) }
        assertEquals(SignInResult.Failed(SignInFailure.BAD_ANSWER), signIn(FakeBrowser { redirectWith(it, "code=c") }).connect())
        google.respond = { _, _ -> OAuthResponse(200, "not json") }
        assertEquals(SignInResult.Failed(SignInFailure.BAD_ANSWER), signIn(FakeBrowser { redirectWith(it, "code=c") }).connect())
        google.respond = { _, _ -> OAuthResponse(400, """{"error":"invalid_grant"}""") }
        assertEquals(SignInResult.Failed(SignInFailure.DENIED), signIn(FakeBrowser { redirectWith(it, "code=c") }).connect())
        assertNull(store.load())
    }

    @Test
    fun aNetworkFailureWhileExchangingIsOfflineNotAStoredHalfGrant() = runTest {
        google.boom = java.io.IOException("down")
        assertEquals(SignInResult.Failed(SignInFailure.OFFLINE), signIn(FakeBrowser { redirectWith(it, "code=c") }).connect())
        assertNull(store.load())
    }

    @Test
    fun aStoreThatCannotBeWrittenKeepsNothingAndRevokes() = runTest {
        google.respond = { url, _ -> if (url == GoogleAuthConfig.REVOKE_ENDPOINT) OAuthResponse(200, "") else tokens() }
        val s = signIn(FakeBrowser { redirectWith(it, "code=c") }, BrokenStore())
        assertEquals(SignInResult.Failed(SignInFailure.STORE_UNAVAILABLE), s.connect())
        assertEquals(GoogleAuthConfig.REVOKE_ENDPOINT, google.posts.last().first)
        assertFalse(s.isConnected())
    }

    @Test
    fun notConfiguredFailsClosedWithoutOpeningABrowser() = runTest {
        val browser = FakeBrowser { BrowserResult.Cancelled }
        val s = signIn(browser, cfg = GoogleAuthConfig.NONE)
        assertFalse(s.configured)
        assertEquals(SignInResult.Failed(SignInFailure.NOT_CONFIGURED), s.connect())
        assertTrue(browser.urls.isEmpty())
    }

    @Test
    fun theAccessTokenIsRefreshedOnTimeFromTheSealedTokenAndNeverWithASecret() = runTest {
        store.save("refresh-0")
        var n = 0
        google.respond = { _, f ->
            assertEquals("refresh_token", f["grant_type"]); assertEquals("refresh-0", f["refresh_token"]); assertNull(f["client_secret"])
            tokens(access = "access-${n++}")
        }
        val s = signIn(FakeBrowser { BrowserResult.Cancelled })
        assertEquals("access-0", s.accessToken())
        now += 30 * 60_000
        assertEquals("access-0", s.accessToken(), "still good")
        now += 31 * 60_000
        assertEquals("access-1", s.accessToken(), "past an hour minus the margin: a new one")
        // A token Drive refused is dropped, so the next call asks again.
        s.onRejected("access-1")
        assertEquals("access-2", s.accessToken())
    }

    @Test
    fun invalidGrantClearsTheTokenAndAsksToConnectAgain() = runTest {
        store.save("refresh-0")
        google.respond = { _, _ -> OAuthResponse(400, """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""") }
        val s = signIn(FakeBrowser { BrowserResult.Cancelled })
        val e = assertFailsWith<DriveException> { s.accessToken() }
        assertEquals(DriveException.Kind.UNAUTHORIZED, e.kind)
        assertNull(store.load()); assertFalse(s.isConnected())
        assertFailsWith<DriveException> { s.accessToken() }
    }

    @Test
    fun aNetworkFailureOrServerErrorWhileRefreshingKeepsTheTokenAndIsRetryable() = runTest {
        store.save("refresh-0")
        val s = signIn(FakeBrowser { BrowserResult.Cancelled })
        google.boom = java.io.IOException("down")
        assertEquals(DriveException.Kind.OFFLINE, assertFailsWith<DriveException> { s.accessToken() }.kind)
        google.boom = null
        google.respond = { _, _ -> OAuthResponse(503, "") }
        assertEquals(DriveException.Kind.OFFLINE, assertFailsWith<DriveException> { s.accessToken() }.kind)
        assertEquals("refresh-0", store.load())
    }

    @Test
    fun noTokenAtAllIsUnauthorizedAndAStoreThatCannotReadFailsClosed() = runTest {
        assertEquals(DriveException.Kind.UNAUTHORIZED, assertFailsWith<DriveException> { signIn(FakeBrowser { BrowserResult.Cancelled }).accessToken() }.kind)
        assertEquals(DriveException.Kind.UNAUTHORIZED, assertFailsWith<DriveException> { signIn(FakeBrowser { BrowserResult.Cancelled }, BrokenStore()).accessToken() }.kind)
    }

    @Test
    fun disconnectForgetsLocallyThenRevokesBestEffortEvenOffline() = runTest {
        store.save("refresh-0")
        google.boom = java.io.IOException("down")
        val s = signIn(FakeBrowser { BrowserResult.Cancelled })
        s.disconnect()
        assertNull(store.load()); assertFalse(s.isConnected())
        assertEquals(GoogleAuthConfig.REVOKE_ENDPOINT, google.posts.single().first)
    }

    @Test
    fun forgetLocallyIsWhatTheLockLossDropsAndNoPrintedFormHoldsATokenOrId() {
        store.save("refresh-secret")
        val s = signIn(FakeBrowser { BrowserResult.Cancelled })
        s.forgetLocally()
        assertNull(store.load())
        assertFalse(config.toString().contains("test-client-id"))
        assertFalse(OAuthFlow.TokenAnswer("acc-secret", "ref-secret", 1, "s", null).toString().contains("secret"))
    }

    @Test
    fun theRedirectParserReadsOnlyOurRedirect() {
        val ok = OAuthFlow.parseRedirect("${config.redirectUri}?state=S&code=C#frag", config, "S")
        assertEquals(OAuthFlow.Redirect.Code("C"), ok)
        assertEquals(OAuthFlow.Redirect.Error("access_denied"), OAuthFlow.parseRedirect("${config.redirectUri}?state=S&error=access_denied", config, "S"))
        assertEquals(OAuthFlow.Redirect.Invalid, OAuthFlow.parseRedirect("${config.redirectUri}x?state=S&code=C", config, "S"))
        assertEquals(OAuthFlow.Redirect.Invalid, OAuthFlow.parseRedirect("${config.redirectUri}?state=S", config, "S"))
        assertEquals(OAuthFlow.Redirect.Invalid, OAuthFlow.parseRedirect(config.redirectUri, config, "S"))
        assertEquals(OAuthFlow.Redirect.Invalid, OAuthFlow.parseRedirect("${config.redirectUri}?state=T&code=C", config, "S"))
    }
}
