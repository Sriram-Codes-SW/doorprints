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

package app.doorprints

import androidx.compose.material3.SnackbarHostState
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.data.SaveWalkResult
import app.doorprints.data.TrackPointEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.ui.offerDeletedHouseUndo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Undo snackbar's non-Undo branch sweeps the deleted house's saved walks (docs/11 5.27.6, TC-U-150): a delete is final
 * when the snackbar closes without *Undo*, or when the screen that showed it is left; *Undo* keeps the walks (they were only
 * hidden).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class DeletedHouseUndoSweepTest {
    private val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository

    @Before fun setUp() = runBlocking {
        repo.saveHouse(HouseEntity(id = "h", label = "Green View", lat = 12.97, lon = 77.6, createdAt = 1, updatedAt = 1))
        val id = 1_000_000L
        (0..5).forEach { k -> repo.saveTrackPoint(TrackPointEntity(at = id + k * 20_000L, lat = 12.97 + k * 0.0003, lon = 77.6, accuracyM = 5f, walkId = id)) }
        assertTrue(repo.saveWalk("h", id) is SaveWalkResult.Saved)
        repo.deleteHouse("h")
    }

    private suspend fun savedWalks() = repo.savedWalkCount.first()

    private suspend fun SnackbarHostState.awaitShown() = withTimeout(5_000) { while (currentSnackbarData == null) delay(5) }

    @Test fun theSnackbarClosingWithoutUndoSweepsTheWalks() = runBlocking {
        val snackbar = SnackbarHostState()
        val job = launch { offerDeletedHouseUndo(repo, snackbar, "h") }
        snackbar.awaitShown()
        // Hidden at once (the house is a tombstone), still stored until the delete is final.
        assertEquals(0, savedWalks())
        snackbar.currentSnackbarData!!.dismiss()
        job.join()
        assertEquals(0, repo.houses.first().size)
        // The delete is final: the walks are swept, not only hidden (a house that returns as a live row has none).
        repo.saveHouse(repo.getHouse("h")!!.copy(deleted = false))
        assertEquals(0, repo.savedWalksOf("h").first().size)
    }

    @Test fun undoBringsTheHouseBackWithItsWalks() = runBlocking {
        val snackbar = SnackbarHostState()
        val job = launch { offerDeletedHouseUndo(repo, snackbar, "h") }
        snackbar.awaitShown()
        snackbar.currentSnackbarData!!.performAction()
        job.join()
        assertEquals(1, repo.houses.first().size)
        assertEquals(1, repo.savedWalksOf("h").first().size)
        assertEquals(1, savedWalks())
    }

    @Test fun leavingTheScreenIsAFinalDeleteToo() = runBlocking {
        val snackbar = SnackbarHostState()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { offerDeletedHouseUndo(repo, snackbar, "h") }
        snackbar.awaitShown()
        job.cancel()
        job.join()
        // The sweep ran under NonCancellable: a house that comes back as a live row has no walks.
        val live = repo.getHouse("h")!!.copy(deleted = false)
        repo.saveHouse(live)
        assertEquals(0, repo.savedWalksOf("h").first().size)
    }

    @Test fun appStartSweepsTheWalksOfADeletedHouse() = runBlocking {
        // The house was deleted and nothing swept (the screen was killed): the next start deletes the walks.
        ApplicationProvider.getApplicationContext<DoorprintsApp>().sweepWalksAtStart().join()
        repo.saveHouse(repo.getHouse("h")!!.copy(deleted = false))
        assertEquals(0, repo.savedWalksOf("h").first().size)
    }
}
