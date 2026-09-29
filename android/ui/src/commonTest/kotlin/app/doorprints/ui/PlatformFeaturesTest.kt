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
            ),
            PlatformFeatures(),
        )
    }

    @Test
    fun iosHidesTheFeaturesItDoesNotHaveYet() {
        assertEquals(
            PlatformFeatures(
                // The map came in CMP-8c.
                map = true,
                huntMode = false,
                addPhotos = false,
                copiesAndImports = false,
                weeklyBackup = false,
                inAppLanguage = false,
            ),
            PlatformFeatures.Ios,
        )
    }
}
