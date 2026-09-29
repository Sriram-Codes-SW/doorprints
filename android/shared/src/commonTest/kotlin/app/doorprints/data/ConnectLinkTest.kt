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

package app.doorprints.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The owner page's connect links (docs/03 §12.1): only an https (or local development) server and a real invite. */
class ConnectLinkTest {
    private val invite = "Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWo"

    @Test
    fun readsTheAppLinkAndTheWebsiteLink() {
        val expected = ConnectLink("https://home.example.ts.net", invite)
        assertEquals(expected, ConnectLink.parse("doorprints://connect?server=https%3A%2F%2Fhome.example.ts.net&invite=$invite"))
        assertEquals(expected, ConnectLink.parse("doorprints://connect?server=https://home.example.ts.net/&invite=$invite"))
        assertEquals(
            expected,
            ConnectLink.parse("https://doorprints.web.app/connect?server=https%3A%2F%2Fhome.example.ts.net&invite=$invite"),
        )
        assertEquals(
            ConnectLink("https://home.example.ts.net:8443", invite),
            ConnectLink.parse("doorprints://connect?server=https://home.example.ts.net:8443&invite=$invite"),
        )
    }

    @Test
    fun allowsTheLocalDevelopmentHostsOverHttp() {
        assertEquals(
            ConnectLink("http://10.0.2.2:8080", invite),
            ConnectLink.parse("doorprints://connect?server=http://10.0.2.2:8080&invite=$invite"),
        )
        assertNull(ConnectLink.parse("doorprints://connect?server=http://home.example.org&invite=$invite"))
    }

    @Test
    fun refusesOtherLinksServersAndInvites() {
        for (link in listOf(
            null,
            "",
            "not a link",
            "doorprints://other?server=https://home.example.org&invite=$invite",
            "https://doorprints.web.app/share?server=https://home.example.org&invite=$invite",
            "http://doorprints.web.app/connect?server=https://home.example.org&invite=$invite",
            "doorprints://connect?invite=$invite",
            "doorprints://connect?server=https://home.example.org",
            "doorprints://connect?server=https://home.example.org/api&invite=$invite",
            "doorprints://connect?server=https://home.example.org/?x=1&invite=$invite",
            "doorprints://connect?server=https://user:pass@home.example.org&invite=$invite",
            "doorprints://connect?server=javascript:alert(1)&invite=$invite",
            "doorprints://connect?server=https://home.example.org&invite=short",
            "doorprints://connect?server=https://home.example.org&invite=$invite!",
        )) {
            assertNull(ConnectLink.parse(link), link)
        }
    }
}
