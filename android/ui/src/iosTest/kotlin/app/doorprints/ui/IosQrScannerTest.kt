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

import app.doorprints.ui.drive.CameraPermission
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import kotlin.test.Test
import kotlin.test.assertEquals

/** The camera permission mapping of the iPhone's QR scanner (S4b-BL-136): AVAuthorizationStatus to [CameraPermission]. */
class IosQrScannerTest {
    @Test
    fun everyAvAuthorizationStatusMapsToItsPermission() {
        assertEquals(CameraPermission.Authorized, cameraPermissionOf(AVAuthorizationStatusAuthorized))
        assertEquals(CameraPermission.NotDetermined, cameraPermissionOf(AVAuthorizationStatusNotDetermined))
        assertEquals(CameraPermission.Denied, cameraPermissionOf(AVAuthorizationStatusDenied))
        assertEquals(CameraPermission.Restricted, cameraPermissionOf(AVAuthorizationStatusRestricted))
    }

    @Test
    fun anUnknownStatusIsTreatedAsRestrictedNeverAsAllowed() {
        assertEquals(CameraPermission.Restricted, cameraPermissionOf(99))
    }
}
