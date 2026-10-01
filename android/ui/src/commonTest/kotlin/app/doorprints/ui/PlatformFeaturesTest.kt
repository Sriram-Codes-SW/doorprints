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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CMP-8b: a platform that provides nothing (Android) has every feature, and the iPhone app hides exactly the ones it
 * does not have yet, so a feature added to [PlatformFeatures] later must be decided for iOS here too.
 */
class PlatformFeaturesTest {

    @Test
    fun theDefaultsHaveEveryFeature() {
        assertEquals(
            PlatformFeatures(
                map = true,
                huntMode = true,
                addPhotos = true,
                copiesAndImports = true,
                weeklyBackup = true,
                inAppLanguage = true,
                areaWakeup = true,
            ),
            PlatformFeatures(),
        )
    }

    @Test
    fun iosHidesTheFeaturesItDoesNotHaveYet() {
        assertEquals(
            PlatformFeatures(
                // The map came in CMP-8c, Hunt mode in S4b-BL-69.
                map = true,
                huntMode = true,
                addPhotos = false,
                copiesAndImports = false,
                weeklyBackup = false,
                inAppLanguage = false,
                // The area wake-up (slice 4b): region monitoring and "Always" on iPhone since S4b-BL-96.
                areaWakeup = true,
            ),
            PlatformFeatures.Ios,
        )
    }
}
