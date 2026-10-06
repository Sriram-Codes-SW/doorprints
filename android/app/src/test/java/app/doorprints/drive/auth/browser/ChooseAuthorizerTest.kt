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
import app.doorprints.drive.auth.AuthorizerResult
import app.doorprints.drive.auth.GoogleAuthorizer
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChooseAuthorizerTest {
    private val play = object : GoogleAuthorizer {
        override suspend fun authorize(scopes: List<String>): AuthorizerResult = AuthorizerResult.Cancelled
        override suspend fun clearToken(accessToken: String) = Unit
        override suspend fun revoke(accessToken: String?, scopes: List<String>) = Unit
    }
    private val browser = object : GoogleAuthorizer by play {}
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun playServicesWhenAvailable() {
        assertSame(play, chooseGoogleAuthorizer(context, play, browser) { true })
    }

    @Test
    fun theBrowserWhenPlayServicesAreMissing() {
        assertSame(browser, chooseGoogleAuthorizer(context, play, browser) { false })
    }

    @Test
    fun theRealCheckAnswersWithoutThrowing() {
        // Robolectric has no Play services: the fallback is chosen (and a throwing check would not crash the choice).
        assertSame(browser, chooseGoogleAuthorizer(context, play, browser))
    }
}
