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

package app.doorprints.drive.device

import android.app.Application
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KeystoreErrorsTest {
    @Test fun `a permanently invalidated key and an unrecoverable key are lost`() {
        assertEquals(DeviceKeyException.Kind.LOST, KeystoreErrors.classify(KeyPermanentlyInvalidatedException()))
        assertEquals(DeviceKeyException.Kind.LOST, KeystoreErrors.classify(UnrecoverableKeyException("x")))
    }

    @Test fun `a user not authenticated error waits`() {
        assertEquals(DeviceKeyException.Kind.NEEDS_UNLOCK, KeystoreErrors.classify(UserNotAuthenticatedException()))
    }

    @Test fun `the cause chain is read`() {
        assertEquals(DeviceKeyException.Kind.LOST, KeystoreErrors.classify(InvalidKeyException("wrapped", KeyPermanentlyInvalidatedException())))
        assertEquals(DeviceKeyException.Kind.NEEDS_UNLOCK, KeystoreErrors.classify(RuntimeException(InvalidKeyException(UserNotAuthenticatedException()))))
    }

    @Test fun `an unknown error says nothing about the key`() {
        assertNull(KeystoreErrors.classify(IllegalStateException("boom")))
        assertNull(KeystoreErrors.classify(null))
    }

    @Test fun `an unknown error waits and never loses the key`() {
        assertEquals(DeviceKeyException.Kind.NEEDS_UNLOCK, KeystoreErrors.toException("x", IllegalStateException("boom")).kind)
    }
}
