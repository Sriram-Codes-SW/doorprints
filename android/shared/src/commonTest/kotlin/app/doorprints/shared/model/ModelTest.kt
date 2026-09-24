package app.doorprints.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** Wire names are shared with the API, the web app and the Room database, so they are pinned here. */
class ModelTest {

    @Test
    fun statusNamesAreStable() {
        assertEquals(listOf("NEW", "SHORTLISTED", "REJECTED"), HouseStatus.entries.map { it.name })
        assertEquals(listOf("AUTO", "MANUAL"), VisitSource.entries.map { it.name })
    }

    @Test
    fun unknownOrMissingWireValuesFallBack() {
        assertEquals(HouseStatus.SHORTLISTED, HouseStatus.fromWire("SHORTLISTED"))
        assertEquals(HouseStatus.NEW, HouseStatus.fromWire(null))
        assertEquals(HouseStatus.NEW, HouseStatus.fromWire("ARCHIVED"))
        assertEquals(HouseStatus.NEW, HouseStatus.fromWire("shortlisted")) // case-sensitive, like Enum.valueOf
        assertEquals(VisitSource.AUTO, VisitSource.fromWire("AUTO"))
        assertEquals(VisitSource.MANUAL, VisitSource.fromWire(null))
        assertEquals(VisitSource.MANUAL, VisitSource.fromWire("IMPORTED"))
    }

    @Test
    fun checklistKeysMatchTheWebAndApi() {
        assertEquals(
            listOf("water", "power", "parking", "sunlight", "ventilation", "noise", "security", "maintenance",
                "neighbourhood", "commute"),
            Checklist.keys,
        )
        assertEquals(20, MAX_PHOTOS_PER_HOUSE)
    }
}
