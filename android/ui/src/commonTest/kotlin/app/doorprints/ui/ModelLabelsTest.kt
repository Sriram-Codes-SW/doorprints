package app.doorprints.ui

import app.doorprints.shared.model.Checklist
import app.doorprints.shared.model.HouseStatus
import app.doorprints.ui.res.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Compose resource labels Compare reads (CMP-4 P4c): one for every shared checklist key, in the shared order and
 * all different, and one for every status, with its glyph. `:app`'s `ModelMappingTest` checks the Android copies.
 */
class ModelLabelsTest {
    @Test
    fun everyChecklistKeyHasItsOwnLabelInTheSharedOrder() {
        assertEquals(Checklist.keys, ChecklistResources.items.keys.toList())
        assertEquals(Checklist.keys.size, ChecklistResources.items.values.toSet().size)
        assertEquals(Res.string.check_water, ChecklistResources.items["water"])
    }

    @Test
    fun everyStatusHasItsLabelAndGlyph() {
        assertEquals(
            listOf(Res.string.status_NEW, Res.string.status_SHORTLISTED, Res.string.status_REJECTED),
            HouseStatus.entries.map { it.labelResource },
        )
        assertEquals(listOf("●", "★", "✕"), HouseStatus.entries.map { it.glyph })
    }
}
