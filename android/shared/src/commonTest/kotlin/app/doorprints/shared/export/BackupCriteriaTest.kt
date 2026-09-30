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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Criteria and preferences in `doorprints-backup/2` (docs/11 5.4, slice 2; docs/schemas README 3.6): `/1` with none,
 * `/2` with criteria only, the lists after `brokers` ordered by `updatedAt` then key, kept without contact details,
 * `counts`, the checks that refuse a bad file, and the import's merge by key with last write wins.
 */
class BackupCriteriaTest {
    private val noise = ExportCriterion("noise", null, 0, false, 3, 5, archived = true, updatedAt = 1_788_328_800_000)
    private val pets = ExportCriterion("c_1a2b3c4d", "Pets allowed", 2, false, 3, 10, updatedAt = 1_788_415_200_000)
    private val water = ExportCriterion("water", null, 3, true, 4, 0, updatedAt = 1_789_029_000_000)
    private val share = ExportPreference("score.ratingShare", "0.4", 1_789_029_000_000)

    private fun bundle(
        options: ExportOptions = ExportFixture.options(),
        criteria: List<ExportCriterion> = listOf(water, noise, pets),
        preferences: List<ExportPreference> = listOf(share),
    ) = ExportBundle.build(
        options, ExportFixture.houses, ExportFixture.visits, ExportFixture.photos, criteria = criteria, preferences = preferences,
    )

    private fun text(b: ExportBundle) = BackupFormat.json.encodeToString(BackupData.serializer(), BackupData.of(b))

    @Test
    fun aCopyWithNoCriterionOrPreferenceIsFormat1WithNeitherKey() {
        val t = text(bundle(criteria = emptyList(), preferences = emptyList()))
        assertTrue(t.startsWith("{\"format\":\"doorprints-backup/1\","), t)
        assertFalse(t.contains("criteria") || t.contains("preferences"), t)
        assertEquals(BackupCounts(2, 1, 1), BackupCounts.of(BackupData.of(bundle(criteria = emptyList(), preferences = emptyList()))))
        assertEquals("doorprints-backup/1", BackupFormat.idFor(0, 0, 0, 0))
    }

    @Test
    fun aCopyWithCriteriaOnlyIsFormat2WithTheListsAfterTheBrokersInTheirOrder() {
        val only = BackupData.of(bundle(preferences = emptyList()))
        assertEquals("doorprints-backup/2", only.format)
        assertNull(only.preferences)
        assertEquals("doorprints-backup/2", BackupFormat.idFor(0, 0, 0, 1))
        val t = text(bundle())
        assertTrue(
            t.endsWith(
                "\"criteria\":[{\"key\":\"noise\",\"weight\":0,\"mustHave\":false,\"minScore\":3,\"sort\":5,\"archived\":true," +
                    "\"updatedAt\":1788328800000},{\"key\":\"c_1a2b3c4d\",\"label\":\"Pets allowed\",\"weight\":2,\"mustHave\":false," +
                    "\"minScore\":3,\"sort\":10,\"updatedAt\":1788415200000},{\"key\":\"water\",\"weight\":3,\"mustHave\":true," +
                    "\"minScore\":4,\"sort\":0,\"updatedAt\":1789029000000}],\"preferences\":[{\"key\":\"score.ratingShare\"," +
                    "\"value\":\"0.4\",\"updatedAt\":1789029000000}]}",
            ),
            t,
        )
        val counts = BackupCounts.of(BackupData.of(bundle()))
        assertEquals(BackupCounts(2, 1, 1, criteria = 3, preferences = 1), counts)
        assertTrue(BackupFormat.json.encodeToString(BackupCounts.serializer(), counts).endsWith("\"photos\":1,\"criteria\":3,\"preferences\":1}"))
    }

    @Test
    fun aCopyWithoutContactDetailsKeepsTheCriteriaAndAnUpdateCarriesTheChangedOnes() {
        val without = BackupData.of(bundle(ExportFixture.options(includeContacts = false)))
        assertEquals("doorprints-backup/2", without.format)
        assertEquals(3, without.criterionRows.size)
        assertEquals(1, without.preferenceRows.size)
        val update = ExportBundle.build(
            ExportFixture.options().copy(since = 1_789_000_000_000), ExportFixture.houses, ExportFixture.visits,
            ExportFixture.photos, criteria = listOf(water, noise, pets), preferences = listOf(share),
        )
        assertEquals(listOf("water"), update.criteria.map { it.key })
        // The scoring of the copy is built from all of them, changed or not.
        assertEquals(true, update.scoring["noise"]!!.archived)
    }

    @Test
    fun theListsReadBackAndAnAbsentListIsNone() {
        val back = BackupFormat.json.decodeFromString(BackupData.serializer(), text(bundle()))
        assertEquals(listOf(noise, pets, water), back.criterionRows)
        assertEquals(listOf(share), back.preferenceRows)
        assertNull(BackupValidation.checkData(back))
        val v1 = BackupFormat.json.decodeFromString(BackupData.serializer(), "{\"format\":\"doorprints-backup/1\",\"exportedAt\":1}")
        assertEquals(emptyList(), v1.criterionRows)
        assertEquals(emptyList(), v1.preferenceRows)
    }

    @Test
    fun aBadCriterionOrPreferenceRefusesTheWholeFile() {
        val good = BackupData.of(bundle())
        fun c(vararg rows: ExportCriterion) = BackupValidation.checkData(good.copy(criteria = rows.toList()))
        fun p(vararg rows: ExportPreference) = BackupValidation.checkData(good.copy(preferences = rows.toList()))
        assertEquals(BackupProblem.BROKEN_DATA, c(water.copy(key = "a/b")))
        assertEquals(BackupProblem.BROKEN_DATA, c(water.copy(weight = 4)))
        assertEquals(BackupProblem.BROKEN_DATA, c(water.copy(weight = -1)))
        assertEquals(BackupProblem.BROKEN_DATA, c(water.copy(minScore = 0)))
        assertEquals(BackupProblem.BROKEN_DATA, c(water.copy(minScore = 6)))
        assertEquals(BackupProblem.BROKEN_DATA, c(water.copy(sort = -1)))
        assertEquals(BackupProblem.BROKEN_DATA, c(pets.copy(label = "x".repeat(61))))
        assertEquals(BackupProblem.BROKEN_DATA, c(water.copy(label = "Water")))
        assertEquals(BackupProblem.BROKEN_DATA, c(water, water))
        val many = (0..40).map { pets.copy(key = "c_" + it.toString(16).padStart(8, '0')) }
        assertEquals(BackupProblem.BROKEN_DATA, c(*many.toTypedArray()))
        assertNull(c(*many.take(40).toTypedArray()))
        assertEquals(BackupProblem.BROKEN_DATA, p(share.copy(key = "../x")))
        assertEquals(BackupProblem.BROKEN_DATA, p(share.copy(value = "v".repeat(501))))
        assertEquals(BackupProblem.BROKEN_DATA, p(share, share))
        // An unparsable share is kept (it reads as 0.5), and 500 characters are fine.
        assertNull(p(share.copy(value = "v".repeat(500))))
        assertEquals(
            BackupProblem.BROKEN_DATA,
            BackupValidation.checkManifest(
                BackupManifest(format = "doorprints-backup/2", createdAt = "x", counts = BackupCounts(0, 0, 0, criteria = -1)),
            ),
        )
    }

    // ---- the import ----

    private fun file(criteria: List<ExportCriterion>, preferences: List<ExportPreference> = emptyList()) =
        BackupData(format = "doorprints-backup/2", exportedAt = 1, criteria = criteria, preferences = preferences)

    private var ids = 0
    private fun plan(data: BackupData, local: Map<String, Long>, mode: ImportMode = ImportMode.MERGE, skip: Boolean = false) =
        ImportPlan.plan(
            data, emptyMap(), emptyMap(), emptySet(), emptySet(), mode, newId = { "id${ids++}" }, skipUpdates = skip,
            localCriteria = local, localPreferences = mapOf(share.key to share.updatedAt),
        )

    private fun preview(data: BackupData, local: Map<String, Long>, mode: ImportMode = ImportMode.MERGE, skip: Boolean = false) =
        ImportPlan.preview(
            data, emptyMap(), emptyMap(), emptySet(), emptySet(), mode, skipUpdates = skip,
            localCriteria = local, localPreferences = mapOf(share.key to share.updatedAt),
        )

    @Test
    fun criteriaMergeByKeyTheNewerWinsAndNothingIsDeleted() {
        val data = file(listOf(water, pets), listOf(share.copy(updatedAt = share.updatedAt + 1)))
        // water is newer in the file, pets is new here; noise (only here) is not touched.
        val local = mapOf(water.key to water.updatedAt - 1, noise.key to noise.updatedAt)
        val actions = plan(data, local)
        assertEquals(listOf(water, pets), actions.criteria)
        assertEquals(1, actions.preferences.size)
        val p = preview(data, local)
        assertEquals(1, p.newCriteria)
        assertEquals(1, p.updatedCriteria)
        assertEquals(0, p.newPreferences)
        assertEquals(1, p.updatedPreferences)
        assertEquals(2, p.overwrites)
        assertFalse(p.isEmpty)
        // Older or equal here: left alone. A tombstone here with a later time keeps the criterion deleted.
        assertEquals(emptyList(), plan(data, mapOf(water.key to water.updatedAt, pets.key to pets.updatedAt + 5)).criteria)
        // "Keep mine": only the new ones.
        assertEquals(listOf(pets), plan(data, local, skip = true).criteria)
        assertEquals(1, preview(data, local, skip = true).newCriteria)
        assertEquals(0, preview(data, local, skip = true).updatedCriteria)
    }

    @Test
    fun aCopyImportKeepsTheKeysAndMergesTheSameWay() {
        val data = file(listOf(water, pets))
        val actions = plan(data, mapOf(water.key to water.updatedAt + 1), ImportMode.COPY)
        assertEquals(listOf(pets), actions.criteria)
        assertEquals("c_1a2b3c4d", actions.criteria.single().key)
        val p = preview(data, mapOf(water.key to water.updatedAt + 1), ImportMode.COPY)
        assertEquals(1, p.newCriteria)
        assertEquals(0, p.updatedCriteria)
    }
}
