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

import android.content.Context
import app.doorprints.deviceauth.AndroidLockLostDetector
import app.doorprints.deviceauth.LockLostDetector

/**
 * The keyguard (`isDeviceSecure`) and, when given, whether the lock-bound device key is still usable: the common
 * [DeviceLockDetectors.withKeyProbe] over [AndroidLockLostDetector]. (The store, the actions and `keyUsable` are common
 * code since the iPhone's Drive.)
 */
fun DeviceLockDetectors.forContext(context: () -> Context?, keyProbe: (() -> Boolean)?, onKeyFault: () -> Unit = {}): LockLostDetector =
    withKeyProbe(AndroidLockLostDetector(context, null), keyProbe, onKeyFault)
