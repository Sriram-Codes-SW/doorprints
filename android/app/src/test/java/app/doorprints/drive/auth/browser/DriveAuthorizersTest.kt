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

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.doorprints.drive.auth.AuthorizerResult
import app.doorprints.drive.auth.ConsentResolver
import app.doorprints.drive.auth.DRIVE_FILE_SCOPE
import app.doorprints.drive.auth.GoogleAuthorizer
import app.doorprints.drive.auth.PendingConsent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.URI
import java.net.URLDecoder

/** The sign-in assembly of the Drive wiring (docs/15 §5.5): Play by default, the browser as fallback, the redirect's way back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DriveAuthorizersTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private class FakePlay : GoogleAuthorizer {
        var authorizeCalls = 0
        override suspend fun authorize(scopes: List<String>): AuthorizerResult {
            authorizeCalls++
            return AuthorizerResult.Granted("play-token", setOf(DRIVE_FILE_SCOPE))
        }
        override suspend fun clearToken(accessToken: String) = Unit
        override suspend fun revoke(accessToken: String?, scopes: List<String>) = Unit
    }

    private class FakeEndpoint : TokenEndpoint {
        var exchanges = 0
        override suspend fun exchangeCode(clientId: String, redirectUri: String, code: String, verifier: String): TokenResult {
            exchanges++
            return TokenResult.Tokens("browser-token", "refresh", setOf(DRIVE_FILE_SCOPE))
        }
        override suspend fun refresh(clientId: String, refreshToken: String): TokenResult = throw AssertionError("no refresh")
        override suspend fun revoke(token: String) = Unit
    }

    private val redirect = BrowserRedirect()
    private val endpoint = FakeEndpoint()
    private var opened = mutableListOf<String>()
    private var playBuilt = 0
    private val play = FakePlay()
    private var clientId: String? = "client-test"

    /** The browser "opens" and the person comes back at once with a code for the request's own state. */
    private val launcher = BrowserLauncher { url ->
        opened += url
        val state = URI(url).rawQuery.split('&').map { it.split('=', limit = 2) }.first { it[0] == "state" }[1]
        redirect.deliver("${BrowserRedirect.DEFAULT_REDIRECT_URI}?code=abc&state=${URLDecoder.decode(state, "UTF-8")}")
        true
    }

    private fun parts(playAvailable: Boolean) = DriveAuthorizers.assemble(
        context, BrowserOAuthConfig({ clientId }), redirect, launcher, endpoint, MemoryRefreshTokenStore(),
        play = { playBuilt++; play }, playAvailable = { playAvailable },
    )

    // ---- which sign-in ----

    @Test
    fun playServicesIsTheSignInWhenItIsAvailableAndTheBrowserStaysClosed() = runBlocking {
        val result = parts(playAvailable = true).authorizer.authorize(listOf(DRIVE_FILE_SCOPE))
        assertEquals("play-token", (result as AuthorizerResult.Granted).accessToken)
        assertEquals(1, play.authorizeCalls)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun theBrowserIsTheSignInWithoutPlayServicesAndPlayIsNeverBuilt() = runBlocking {
        val p = parts(playAvailable = false)
        val needs = p.authorizer.authorize(listOf(DRIVE_FILE_SCOPE)) as AuthorizerResult.NeedsConsent
        assertTrue(needs.consent is BrowserPendingConsent)
        assertEquals("no browser opens from authorize alone", 0, opened.size)
        assertEquals(0, play.authorizeCalls)
        assertEquals("a phone without Play services never constructs the Play authorizer", 0, playBuilt)
    }

    @Test
    fun thePlayAuthorizerIsBuiltOnlyWhenFirstUsed() = runBlocking {
        val p = parts(playAvailable = true)
        assertEquals(0, playBuilt)
        p.authorizer.authorize(listOf(DRIVE_FILE_SCOPE))
        p.authorizer.authorize(listOf(DRIVE_FILE_SCOPE))
        assertEquals(1, playBuilt)
    }

    // ---- a blank client id ----

    @Test
    fun aBlankClientIdIsUnavailableAndOpensNothing() = runBlocking {
        for (blank in listOf(null, "", "   ")) {
            clientId = blank
            val p = parts(playAvailable = false)
            val asked = p.authorizer.authorize(listOf(DRIVE_FILE_SCOPE))
            assertEquals(AuthorizerResult.FailureKind.UNAVAILABLE, (asked as AuthorizerResult.Failed).kind)
            // Even a consent somebody already holds does nothing without an id.
            val resolved = DriveAuthorizers.resolver({ true }, { null })()!!.resolve(BrowserPendingConsent(p.browser))
            assertEquals(AuthorizerResult.FailureKind.UNAVAILABLE, (resolved as AuthorizerResult.Failed).kind)
        }
        assertTrue("nothing opened", opened.isEmpty())
        assertEquals(0, endpoint.exchanges)
    }

    @Test
    fun theDriveCardIsAvailableOnlyWithPlayServicesOrAClientId() {
        assertTrue(DriveAuthorizers.configured(playAvailable = true, clientId = ""))
        assertTrue(DriveAuthorizers.configured(playAvailable = false, clientId = "abc.apps.googleusercontent.com"))
        assertFalse(DriveAuthorizers.configured(playAvailable = false, clientId = ""))
        assertFalse(DriveAuthorizers.configured(playAvailable = false, clientId = "  "))
    }

    // ---- the consent resolver ----

    @Test
    fun withNoActivityThereIsNoResolverSoNoBrowserOpensByItself() {
        assertNull(DriveAuthorizers.resolver({ false }, { throw AssertionError("not asked") })())
    }

    @Test
    fun bothKindsOfConsentResolve() = runBlocking {
        val p = parts(playAvailable = false)
        val playConsent = object : PendingConsent {}
        val playResolver = object : ConsentResolver {
            override suspend fun resolve(consent: PendingConsent): AuthorizerResult = AuthorizerResult.Granted("from-play-screen", setOf(DRIVE_FILE_SCOPE))
        }
        val resolver = DriveAuthorizers.resolver({ true }, { playResolver })()!!
        assertEquals("from-play-screen", (resolver.resolve(playConsent) as AuthorizerResult.Granted).accessToken)
        val browserAnswer = resolver.resolve(BrowserPendingConsent(p.browser)) as AuthorizerResult.Granted
        assertEquals("browser-token", browserAnswer.accessToken)
        assertEquals(1, opened.size)
        assertEquals(1, endpoint.exchanges)
    }

    // ---- the way back from the browser ----

    private fun redirectIntent(state: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse("${BrowserRedirect.DEFAULT_REDIRECT_URI}?code=abc&state=$state"))

    @Test
    fun theRedirectIntentIsTakenOnceAndEmptied() {
        val request = redirect.begin("s1")
        val intent = redirectIntent("s1")
        assertTrue(DriveAuthorizers.deliver(redirect, intent))
        assertNull("a recreation of the Activity cannot replay it", intent.data)
        assertNotNull(request)
        assertFalse("a replay of the same redirect is refused", DriveAuthorizers.deliver(redirect, redirectIntent("s1")))
    }

    @Test
    fun anyOtherIntentIsLeftAloneForTheDeepLinkCode() {
        redirect.begin("s1")
        val wrongState = redirectIntent("other")
        assertFalse(DriveAuthorizers.deliver(redirect, wrongState))
        assertNotNull(wrongState.data)
        val connect = Intent(Intent.ACTION_VIEW, Uri.parse("doorprints://connect?x=1"))
        assertFalse(DriveAuthorizers.deliver(redirect, connect))
        assertNotNull(connect.data)
        assertFalse(DriveAuthorizers.deliver(redirect, null))
    }
}
