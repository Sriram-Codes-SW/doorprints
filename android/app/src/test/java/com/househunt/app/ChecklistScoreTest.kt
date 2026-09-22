package com.househunt.app

import com.househunt.app.data.HouseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** HouseEntity.score: checklist average, blended 50/50 with the star rating when both exist; null when neither. */
class ChecklistScoreTest {

    private fun house(checklist: Map<String, Int> = emptyMap(), rating: Int? = null) =
        HouseEntity(id = "h1", label = "Flat", lat = 0.0, lon = 0.0, checklist = checklist, rating = rating,
            createdAt = 0, updatedAt = 0)

    @Test
    fun nothingScoredIsNull() {
        assertNull(house().score)
    }

    @Test
    fun checklistOnlyIsItsAverage() {
        assertEquals(5.0, house(mapOf("water" to 5)).score!!, 1e-9)
        assertEquals(3.5, house(mapOf("water" to 5, "power" to 2)).score!!, 1e-9)
        assertEquals(4.0 / 3, house(mapOf("water" to 1, "power" to 1, "noise" to 2)).score!!, 1e-9)
    }

    @Test
    fun ratingOnlyIsTheRating() {
        assertEquals(4.0, house(rating = 4).score!!, 1e-9)
    }

    @Test
    fun zeroIsAScoreNotMissing() {
        assertEquals(0.0, house(mapOf("water" to 0, "power" to 0)).score!!, 1e-9)
        assertEquals(0.0, house(rating = 0).score!!, 1e-9)
        assertEquals(2.5, house(mapOf("water" to 0), rating = 5).score!!, 1e-9)
    }

    @Test
    fun checklistAndRatingBlendHalfAndHalf() {
        // Checklist average 3 (4 and 2), rating 5: (3 + 5) / 2 = 4, however many checklist items there are.
        assertEquals(4.0, house(mapOf("water" to 4, "power" to 2), rating = 5).score!!, 1e-9)
        val all = house(mapOf("water" to 5, "power" to 5, "parking" to 5, "noise" to 5), rating = 1)
        assertEquals(3.0, all.score!!, 1e-9)
    }
}
