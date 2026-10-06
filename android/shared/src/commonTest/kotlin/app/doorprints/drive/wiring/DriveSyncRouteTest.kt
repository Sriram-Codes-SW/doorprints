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

import app.doorprints.data.SyncBackend
import app.doorprints.shared.api.HouseDto
import app.doorprints.shared.api.PhotoChangeDto
import app.doorprints.shared.api.PhotoMetaDto
import app.doorprints.shared.api.RecordDto
import app.doorprints.shared.api.VisitDto
import app.doorprints.shared.sync.MergeRule
import app.doorprints.shared.sync.SyncRules
import kotlinx.io.Source
import app.doorprints.testing.blocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class DriveSyncRouteTest {
    /** A backend that is only ever compared by identity: nothing in these tests calls it. */
    private class Marker : SyncBackend {
        override val mergeRule: MergeRule get() = SyncRules.serverMerge
        override suspend fun isBehind(cursors: List<Long>) = false
        override suspend fun pushHouse(house: HouseDto): HouseDto = house
        override suspend fun pushVisit(visit: VisitDto): VisitDto = visit
        override suspend fun pushRecord(record: RecordDto): RecordDto = record
        override suspend fun deletePhoto(photoId: String) = Unit
        override suspend fun uploadPhoto(houseId: String, photoId: String, fileName: String, size: Long, open: () -> Source) = Unit
        override suspend fun pushPhotoMeta(photoId: String, meta: PhotoMetaDto): PhotoChangeDto? = null
        override suspend fun housesSince(cursor: Long): List<HouseDto> = emptyList()
        override suspend fun visitsSince(cursor: Long): List<VisitDto> = emptyList()
        override suspend fun recordsSince(cursor: Long): List<RecordDto> = emptyList()
        override suspend fun photoChangesSince(cursor: Long): List<PhotoChangeDto> = emptyList()
        override suspend fun downloadPhoto(photoId: String): ByteArray = ByteArray(0)
    }

    private fun backend(): SyncBackend = Marker()

    @Test
    fun theBackendIsSeenInsideItsPassAndNowhereElse() = blocking {
        val route = DriveSyncRoute()
        val drive = backend()
        assertNull(route.current())
        withContext(route.element(drive)) {
            assertSame(drive, route.current())
            // The loop's own switches of dispatcher and its child coroutines keep it.
            coroutineScope { assertSame(drive, async { route.current() }.await()) }
        }
        assertNull(route.current())
    }

    @Test
    fun aSecondRouteAndASiblingCoroutineSeeNothing() = blocking {
        val route = DriveSyncRoute()
        val other = DriveSyncRoute()
        val drive = backend()
        coroutineScope {
            val sibling = async { route.current() }
            withContext(route.element(drive)) {
                assertNull(other.current(), "another route's element is not this route's")
                assertSame(drive, route.current())
            }
            assertNull(sibling.await(), "a server sync running meanwhile sees no Drive backend")
        }
    }

    @Test
    fun aNestedPassSeesTheInnerBackendAndThenTheOuter() = blocking {
        val route = DriveSyncRoute()
        val outer = backend()
        val inner = backend()
        withContext(route.element(outer)) {
            withContext(route.element(inner)) { assertSame(inner, route.current()) }
            assertSame(outer, route.current())
        }
    }
}
