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

import android.content.Context
import android.content.Intent
import app.doorprints.drive.auth.AuthorizerResult
import app.doorprints.drive.auth.ConsentResolver
import app.doorprints.drive.auth.GoogleAuthorizer

/** A [GoogleAuthorizer] built on first use, so a phone without Google Play services never constructs the Play one. */
class LazyGoogleAuthorizer(create: () -> GoogleAuthorizer) : GoogleAuthorizer {
    private val inner by lazy(create)
    override suspend fun authorize(scopes: List<String>): AuthorizerResult = inner.authorize(scopes)
    override suspend fun clearToken(accessToken: String) = inner.clearToken(accessToken)
    override suspend fun revoke(accessToken: String?, scopes: List<String>) = inner.revoke(accessToken, scopes)
}

/**
 * How the Drive sign-in is put together from its two kinds (docs/15 §5.5, owner decision of 2026-10-06): Play services is
 * the default, the system browser with PKCE the fallback. Pure of Android singletons so a JVM test builds it over fakes.
 */
object DriveAuthorizers {
    class Parts(val authorizer: GoogleAuthorizer, val browser: BrowserGoogleAuthorizer)

    /** The sign-in this phone uses: Play's (built only when asked) when [playAvailable] says so, else the browser's. */
    fun assemble(
        context: Context,
        config: BrowserOAuthConfig,
        redirect: BrowserRedirect,
        launcher: BrowserLauncher,
        endpoint: TokenEndpoint,
        store: RefreshTokenStore,
        play: () -> GoogleAuthorizer,
        playAvailable: (Context) -> Boolean = ::playServicesAvailable,
    ): Parts {
        val browser = BrowserGoogleAuthorizer(config, redirect, launcher, endpoint, store)
        return Parts(chooseGoogleAuthorizer(context, LazyGoogleAuthorizer(play), browser, playAvailable), browser)
    }

    /**
     * The consent resolver handed to the token provider: only while an Activity is on screen (a background run has none, so
     * the provider says "consent required" and no browser ever opens by itself); then it resolves both kinds of consent.
     */
    fun resolver(activityOnScreen: () -> Boolean, play: () -> ConsentResolver?): () -> ConsentResolver? =
        { if (activityOnScreen()) BrowserAwareResolver(play()) else null }

    /** Drive can be connected when Play services is there or a client id for the browser path was built in. */
    fun configured(playAvailable: Boolean, clientId: String): Boolean = playAvailable || clientId.isNotBlank()

    /** `MainActivity`'s hook: true when [intent] was the sign-in's redirect; it is then emptied so a recreation cannot replay it. */
    fun deliver(redirect: BrowserRedirect, intent: Intent?): Boolean {
        val taken = redirect.onNewIntent(intent)
        if (taken) intent?.data = null
        return taken
    }
}
