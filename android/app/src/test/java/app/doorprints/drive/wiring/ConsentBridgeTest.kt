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

package app.doorprints.drive.wiring

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.doorprints.drive.auth.AuthorizerResult
import app.doorprints.drive.auth.DRIVE_FILE_SCOPE
import app.doorprints.drive.auth.PendingConsent
import app.doorprints.drive.auth.PlayPendingConsent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Activity side of Google's consent (A1 notes): the bridge starts Google's screen on the foreground Activity and has
 * Google's own reader (`PlayGoogleAuthorizer.fromActivityResult`, here a fake) interpret the answer. A `PendingIntent` needs
 * Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConsentBridgeTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun consent() = PlayPendingConsent(PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE))

    private class Starter(var outcome: ActivityOutcome) : ActivityLauncher {
        var launched = 0
        override suspend fun launch(consent: PendingIntent): ActivityOutcome {
            launched++
            return outcome
        }
        override suspend fun launch(intent: Intent): ActivityOutcome = outcome
    }

    private fun granted(token: String = "tok") = AuthorizerResult.Granted(token, setOf(DRIVE_FILE_SCOPE))

    @Test
    fun theConsentAnswerIsReadByGoogleSInterpreter() = runBlocking {
        val starter = Starter(ActivityOutcome(Activity.RESULT_OK, null))
        val seen = mutableListOf<Int>()
        val bridge = ConsentBridge({ starter }, { code, _ -> seen += code; granted("after-consent") })
        val answer = bridge.resolve(consent())
        assertEquals(1, starter.launched)
        assertEquals(listOf(Activity.RESULT_OK), seen)
        assertEquals("after-consent", (answer as AuthorizerResult.Granted).accessToken)
    }

    @Test
    fun aClosedConsentScreenStaysCancelledNotDenied() = runBlocking {
        val starter = Starter(ActivityOutcome(Activity.RESULT_CANCELED, null))
        val bridge = ConsentBridge({ starter }, { code, _ -> if (code == Activity.RESULT_CANCELED) AuthorizerResult.Cancelled else granted() })
        assertEquals(AuthorizerResult.Cancelled, bridge.resolve(consent()))
    }

    @Test
    fun aScreenAnsweredWithoutTheDrivePermissionIsPassedOnForTheProviderToRefuse() = runBlocking {
        val starter = Starter(ActivityOutcome(Activity.RESULT_OK, null))
        val bridge = ConsentBridge({ starter }, { _, _ -> AuthorizerResult.Granted("tok", emptySet()) })
        val answer = bridge.resolve(consent()) as AuthorizerResult.Granted
        assertTrue(DRIVE_FILE_SCOPE !in answer.grantedScopes)
    }

    @Test
    fun withNoActivityTheConsentIsUnavailableNotRefused() = runBlocking {
        val bridge = ConsentBridge({ null }, { _, _ -> granted() })
        assertNull(bridge.resolverOrNull())
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE), bridge.resolve(consent()))
    }

    @Test
    fun withAnActivityTheBridgeIsTheResolver() {
        val bridge = ConsentBridge({ Starter(ActivityOutcome(Activity.RESULT_OK, null)) }, { _, _ -> granted() })
        assertTrue(bridge.resolverOrNull() === bridge)
    }

    @Test
    fun aConsentThatIsNotGooglesIsUnavailable() = runBlocking {
        val bridge = ConsentBridge({ Starter(ActivityOutcome(Activity.RESULT_OK, null)) }, { _, _ -> granted() })
        val other = object : PendingConsent {}
        assertEquals(AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE), bridge.resolve(other))
    }
}
