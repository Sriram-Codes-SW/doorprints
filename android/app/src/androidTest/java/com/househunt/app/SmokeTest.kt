package com.househunt.app

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.services.storage.TestStorage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests of the installed debug APK on an emulator (android-emulator.yml) or a Test Lab device (docs/06
 * TC-I-35): the app starts, every tab opens (the Assistant tab only exists with the server's AI features on, so it is
 * not among them), a house can be added from a "new house" intent and then shows in the
 * list. Each step saves a screenshot to the test storage (CI artifact). The device language is English.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *listOfNotNull(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= 33 },
        ).toTypedArray(),
    )

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun tearDown() {
        scenario?.close()
    }

    private fun launch(intent: Intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)) {
        scenario = ActivityScenario.launch(intent)
    }

    private fun waitFor(text: String, timeoutMs: Long = 20_000) =
        compose.waitUntilAtLeastOneExists(hasText(text), timeoutMs)

    private fun tab(label: String): SemanticsNodeInteraction =
        compose.onAllNodes(hasText(label) and hasClickAction()).onFirst()

    /** A screenshot in the test storage; Test Lab runs without the storage service, so a failure is not fatal. */
    private fun shot(name: String) {
        compose.waitForIdle()
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            TestStorage().openOutputFile("screens/$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun everyTabOpens() {
        launch()
        waitFor("Houses")
        shot("01_map")
        for ((label, name) in listOf("Houses" to "02_houses", "Compare" to "03_compare", "Settings" to "04_settings", "Map" to "05_map_again")) {
            tab(label).performClick()
            shot(name)
        }
    }

    @Test fun addAHouseFromANewHouseIntent() {
        val name = "Smoke test house ${System.currentTimeMillis() % 100_000}"
        launch(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra(Notifications.EXTRA_NEW_LAT, 12.9716)
                .putExtra(Notifications.EXTRA_NEW_LON, 77.5946),
        )
        compose.waitUntilAtLeastOneExists(hasSetTextAction(), 20_000)
        // Replace, not append: the form starts with a default name ("New house", or a street from the geocoder).
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement(name)
        shot("10_new_house")
        // The top bar's Save: the form has a second one at its end, off screen inside the scrolling column.
        compose.onNode(hasText("Save") and hasClickAction() and !hasAnyAncestor(hasScrollAction())).performClick()
        // The name is on the new-house form already, so wait for the saved house's own page ("House details").
        waitFor("House details")
        shot("11_saved")
        compose.onAllNodes(hasContentDescription("Back") and hasClickAction()).onFirst().performClick()
        tab("Houses").performClick()
        waitFor(name)
        shot("12_in_list")
    }
}
