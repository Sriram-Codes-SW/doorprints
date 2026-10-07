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

package app.doorprints.screenshots

import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.DoorprintsApp
import app.doorprints.data.TourEnd
import app.doorprints.i18n.AppLocale
import app.doorprints.ui.DeepLink
import app.doorprints.ui.DoorprintsRoot
import app.doorprints.ui.DoorprintsTheme
import app.doorprints.ui.PlatformFeatures
import app.doorprints.ui.ProvideAppServices
import app.doorprints.ui.TourContext
import app.doorprints.ui.TourSteps
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.nav_settings
import app.doorprints.ui.res.tour_next
import app.doorprints.ui.res.tour_replay
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * Two shots of the guided tour (S4b-FR-39) on the real root, to see that the highlight is cut out over its control and
 * the card sits clear of it: the Criteria step of Settings in English, light (the card below the row), and the Help step in
 * Tamil, dark, at 1.5 times the font size (the card above it). Few and small on purpose: the tour's rules are in
 * `TourTest` and `TourOverlayTest`. Record with `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-hdpi", application = ScreenshotTestApp::class)
class TourScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val timeZone: TimeZone = TimeZone.getDefault()
    private val locales: LocaleList = LocaleList.getDefault()

    @After fun tearDown() {
        TimeZone.setDefault(timeZone)
        LocaleList.setDefault(locales)
    }

    private fun text(res: StringResource): String = runBlocking { getString(res) }

    private fun shoot(lang: String, dark: Boolean, fontScale: Float, stepId: String, file: String) {
        RuntimeEnvironment.setQualifiers("+$lang" + if (dark) "-night" else "-notnight")
        AppLocale.applyDefault(ApplicationProvider.getApplicationContext())
        val application = ApplicationProvider.getApplicationContext<DoorprintsApp>()
        val services = application.container.services
        runBlocking { application.container.repository.settings.setTourEnd(TourEnd.SKIPPED) }
        val steps = TourSteps.forPhone(TourContext(PlatformFeatures(), assistant = false, offlineMaps = services.offlineMaps.supported))
        val presses = steps.indexOfFirst { it.id == stepId }
        check(presses > 0) { "no step $stepId" }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                ProvideAppServices {
                    DoorprintsTheme(dark = dark) {
                        DoorprintsRoot(MutableStateFlow<DeepLink?>(null), onDeepLinkHandled = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(text(Res.string.nav_settings)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(Res.string.tour_replay)).performScrollTo().performClick()
        compose.waitForIdle()
        repeat(presses) {
            compose.onNodeWithText(text(Res.string.tour_next)).performClick()
            compose.waitForIdle()
        }
        // The scroll to the target and the frame after it.
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        check(compose.onAllNodesWithText(text(Res.string.tour_next)).fetchSemanticsNodes().isNotEmpty())
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun criteriaStepEnglishLight() = shoot("en", dark = false, fontScale = 1f, stepId = "criteria", file = "src/test/screenshots/tour_criteria_en_light.png")

    @Test
    fun helpStepTamilDarkLargeFont() = shoot("ta", dark = true, fontScale = 1.5f, stepId = "help", file = "src/test/screenshots/tour_help_ta_dark.png")
}
