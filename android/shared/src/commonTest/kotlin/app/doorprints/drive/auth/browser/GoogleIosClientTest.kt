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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleIosClientTest {
    @Test
    fun theRedirectIsTheReversedClientIdAsACustomScheme() {
        val client = assertNotNull(GoogleIosClient.of("123456789-abcdef.apps.googleusercontent.com"))
        assertEquals("123456789-abcdef.apps.googleusercontent.com", client.clientId)
        assertEquals("com.googleusercontent.apps.123456789-abcdef", client.urlScheme)
        assertEquals("com.googleusercontent.apps.123456789-abcdef:/oauth2redirect", client.redirectUri)
    }

    @Test
    fun theRedirectIsOneTheBrowserRedirectAccepts() {
        val client = assertNotNull(GoogleIosClient.of("1-a.apps.googleusercontent.com"))
        val redirect = BrowserRedirect(client.redirectUri)
        val request = redirect.begin("s1")
        assertTrue(redirect.deliver("${client.redirectUri}?state=s1&code=c"))
        assertEquals("c", (app.doorprints.testing.blocking { request.await() } as BrowserRedirect.Outcome.Code).code)
    }

    @Test
    fun whitespaceAroundTheIdIsDropped() {
        assertNotNull(GoogleIosClient.of("  1-a.apps.googleusercontent.com\n"))
    }

    @Test
    fun anythingThatIsNotAClientIdIsNoClient() {
        for (raw in listOf(
            null, "", "   ", "abc", "1-a", "\$(GOOGLE_IOS_CLIENT_ID)", "1-a.apps.example.com", ".apps.googleusercontent.com", "-a.apps.googleusercontent.com",
            "a b.apps.googleusercontent.com", "a:b.apps.googleusercontent.com", "a/b.apps.googleusercontent.com", "a.b.apps.googleusercontent.com",
            "a".repeat(129) + ".apps.googleusercontent.com", "1-a.apps.googleusercontent.com.evil",
        )) {
            assertNull(GoogleIosClient.of(raw), "$raw")
        }
    }

    @Test
    fun theClientIdIsNotInItsToString() {
        assertEquals("GoogleIosClient", assertNotNull(GoogleIosClient.of("1-a.apps.googleusercontent.com")).toString())
    }
}
