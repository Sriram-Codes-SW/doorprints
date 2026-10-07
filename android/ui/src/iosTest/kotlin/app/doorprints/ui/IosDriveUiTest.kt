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

package app.doorprints.ui

import app.doorprints.drive.wiring.LockNotice
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.lock_notice_ios_key_lost
import app.doorprints.ui.res.lock_notice_ios_needs
import app.doorprints.ui.res.lock_notice_ios_paused
import platform.UIKit.UIPasteboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The iPhone's Drive pieces that run on a plain simulator test host. */
class IosDriveUiTest {
    @Test
    fun eachNoticeHasItsOwnWordsAndNoneIsLeftOver() {
        assertSame(Res.string.lock_notice_ios_needs, LockNotice.NEEDS_LOCK.iosMessage())
        assertSame(Res.string.lock_notice_ios_paused, LockNotice.PAUSED.iosMessage())
        assertSame(Res.string.lock_notice_ios_key_lost, LockNotice.KEY_LOST.iosMessage())
    }

    @Test
    fun aTestBinaryHasNoGoogleClientSoDriveIsNotAvailable() {
        // Info.plist's GoogleIOSClientId is empty in the repository and absent from the test host's bundle.
        assertNull(IosDriveServices.googleClient())
    }

    @Test
    fun theDeviceNameIsTheModelAndTheAppNotTheOwnersName() {
        val name = IosDriveServices.iosDeviceName()
        assertTrue(name.endsWith(" (Doorprints app)"), name)
        assertTrue(name.startsWith("iPhone") || name.startsWith("iPad"), name)
    }

    @Test
    fun aCopiedSecretIsOnThePasteboardForThePersonToPaste() {
        // A headless simulator test host may have no working pasteboard (the Keychain test skips the same way): if a plain
        // string does not even read back, there is nothing to assert about the seam on this host.
        UIPasteboard.generalPasteboard.string = "probe"
        if (UIPasteboard.generalPasteboard.string != "probe") return
        IosClipboardSeam().copySensitive("abcd-efgh-ijkl")
        assertEquals("abcd-efgh-ijkl", UIPasteboard.generalPasteboard.string)
        UIPasteboard.generalPasteboard.string = ""
    }
}
