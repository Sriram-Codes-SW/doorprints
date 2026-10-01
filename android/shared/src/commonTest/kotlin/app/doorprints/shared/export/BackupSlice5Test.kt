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

package app.doorprints.shared.export

import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.MoveIn
import app.doorprints.shared.model.MoveInItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 5 in a backup (docs/11 5.7, 5.24): the statuses TAKEN and NOT_CHOSEN, a house's `moveIn` after `answers`, a
 * photo's meta after `createdAt`; each of them makes the file `/2`; the check refuses a bad move-in or bad meta; an
 * import merges a photo's meta by `metaUpdatedAt`.
 */
class BackupSlice5Test {
    private val moveIn = MoveIn(
        1_790_812_800_000L, "Keys handed over by Ravi.",
        listOf(MoveInItem("mi_agreement", "Rental agreement signed and registered", true, 0), MoveInItem("mi_police", "Police verification done", sort = 1)),
    )
    private val room = HouseRoom("c1", "KITCHEN", "Kitchen", sort = 0)
    private val metaPhoto = ExportFixture.photo1.copy(
        roomId = "c1", tags = listOf("KITCHEN_FITTINGS", "MOVE_IN", "damp corner"), caption = "Tap drips slightly.",
        metaUpdatedAt = 1_790_000_000_000L,
    )

    private fun data(b: ExportBundle) = BackupFormat.json.encodeToString(BackupData.serializer(), BackupData.of(b))

    private fun bundle(houses: List<ExportHouse>, photos: List<ExportPhoto> = ExportFixture.photos) =
        ExportBundle.build(ExportFixture.options(), houses, ExportFixture.visits, photos)

    @Test
    fun aTakenOrNotChosenHouseAMoveInOrAPhotoWithMetaMakesTheFileFormat2() {
        assertTrue(data(ExportFixture.bundle()).startsWith("{\"format\":\"doorprints-backup/1\","))
        for (b in listOf(
            bundle(listOf(ExportFixture.house1.copy(status = "TAKEN"), ExportFixture.house2)),
            bundle(listOf(ExportFixture.house1, ExportFixture.house2.copy(status = "NOT_CHOSEN"))),
            bundle(listOf(ExportFixture.house1.copy(moveIn = moveIn), ExportFixture.house2)),
            bundle(listOf(ExportFixture.house1, ExportFixture.house2), listOf(ExportFixture.photo1.copy(caption = "Hall"))),
        )) assertTrue(data(b).startsWith("{\"format\":\"doorprints-backup/2\","), data(b))
        assertEquals(BackupFormat.ID_2, BackupFormat.idFor(0, slice5 = true))
    }

    @Test
    fun theMoveInFollowsTheAnswersAndThePhotoMetaFollowsCreatedAt() {
        val text = data(bundle(listOf(ExportFixture.house1.copy(status = "TAKEN", rooms = listOf(room), moveIn = moveIn), ExportFixture.house2), listOf(metaPhoto)))
        assertTrue(
            text.contains(
                "\"moveIn\":{\"date\":1790812800000,\"notes\":\"Keys handed over by Ravi.\",\"items\":[" +
                    "{\"id\":\"mi_agreement\",\"text\":\"Rental agreement signed and registered\",\"done\":true,\"sort\":0}," +
                    "{\"id\":\"mi_police\",\"text\":\"Police verification done\",\"sort\":1}]}",
            ),
            text,
        )
        assertTrue(
            text.contains(
                "\"createdAt\":1790004000000,\"roomId\":\"c1\",\"tags\":[\"KITCHEN_FITTINGS\",\"MOVE_IN\",\"damp corner\"]," +
                    "\"caption\":\"Tap drips slightly.\",\"metaUpdatedAt\":1790000000000}",
            ),
            text,
        )
        val back = BackupFormat.json.decodeFromString(BackupData.serializer(), text)
        assertNull(BackupValidation.checkData(back))
        assertEquals(moveIn, back.houses.first().moveIn)
        assertEquals(metaPhoto, back.photos.single())
        // A photo without meta writes none of the four keys.
        assertFalse(data(ExportFixture.bundle()).contains("\"metaUpdatedAt\""))
        // Kept in a copy without contact details.
        val without = BackupData.of(
            ExportBundle.build(ExportFixture.options(includeContacts = false), listOf(ExportFixture.house1.copy(moveIn = moveIn)), emptyList(), listOf(metaPhoto)),
        )
        assertEquals(moveIn, without.houses.single().moveIn)
        assertEquals("damp corner", without.photos.single().tags!!.last())
    }

    @Test
    fun aBadMoveInOrBadPhotoMetaRefusesTheWholeFile() {
        val good = BackupData.of(bundle(listOf(ExportFixture.house1.copy(moveIn = moveIn), ExportFixture.house2), listOf(metaPhoto)))
        assertNull(BackupValidation.checkData(good))
        fun withMoveIn(m: MoveIn) = good.copy(houses = listOf(ExportFixture.house1.copy(moveIn = m), ExportFixture.house2))
        for (bad in listOf(
            moveIn.copy(date = 0), moveIn.copy(notes = "n".repeat(2001)),
            moveIn.copy(items = listOf(MoveInItem("a", "t"), MoveInItem("a", "u"))), moveIn.copy(items = listOf(MoveInItem("a/b", "t"))),
            moveIn.copy(items = listOf(MoveInItem("a", ""))), moveIn.copy(items = listOf(MoveInItem("a", "t", sort = -1))),
            moveIn.copy(items = (0..30).map { MoveInItem("i$it", "t", sort = it) }),
        )) assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(withMoveIn(bad)), bad.toString())
        fun withPhoto(p: ExportPhoto) = good.copy(photos = listOf(p))
        for (bad in listOf(
            metaPhoto.copy(tags = (1..11).map { "t$it" }), metaPhoto.copy(tags = listOf("x".repeat(31))),
            metaPhoto.copy(tags = listOf("damp")), metaPhoto.copy(tags = listOf("Corner", "corner")),
            metaPhoto.copy(caption = "c".repeat(201)), metaPhoto.copy(roomId = "r".repeat(65)), metaPhoto.copy(metaUpdatedAt = -1),
        )) assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(withPhoto(bad)), bad.toString())
    }

    @Test
    fun anImportMergesAPhotosMetaByMetaUpdatedAtAndAnOlderRowChangesNothing() {
        val file = BackupData(format = BackupFormat.ID_2, exportedAt = 1, houses = listOf(ExportFixture.house1), photos = listOf(metaPhoto))
        val zip = setOf("photos/p1.jpg")
        fun preview(local: Long) = ImportPlan.preview(
            file, mapOf("h1" to ExportFixture.house1.updatedAt), emptyMap(), setOf("p1"), zip, ImportMode.MERGE, localPhotoMeta = mapOf("p1" to local),
        )
        fun plan(local: Long, skip: Boolean = false) = ImportPlan.plan(
            file, mapOf("h1" to ExportFixture.house1.updatedAt), emptyMap(), setOf("p1"), zip, ImportMode.MERGE, newId = { "x" },
            skipUpdates = skip, localPhotoMeta = mapOf("p1" to local),
        )
        assertEquals(1, preview(0).updatedPhotoMeta)
        assertFalse(preview(0).isEmpty)
        assertEquals(listOf(metaPhoto), plan(0).photoMeta)
        assertTrue(plan(0).photos.isEmpty(), "the photo is on the phone: only its meta is written")
        assertEquals(0, preview(metaPhoto.metaUpdatedAt!!).updatedPhotoMeta, "the same time changes nothing")
        assertTrue(plan(metaPhoto.metaUpdatedAt!! + 1).photoMeta.isEmpty(), "a newer edit here stays")
        assertTrue(plan(0, skip = true).photoMeta.isEmpty(), "Keep mine leaves it")
        // A new photo brings its meta with it.
        val fresh = ImportPlan.plan(file, emptyMap(), emptyMap(), emptySet(), zip, ImportMode.MERGE, newId = { "x" })
        assertEquals(metaPhoto, fresh.photos.single())
    }
}
