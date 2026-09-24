package app.doorprints.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which persisted "Save to…" grants the app keeps (see `ExportGrants`).
 *
 * The grant has to be persisted, or it ends with the activity and a backed-out export can neither clean up after a
 * Stop nor be opened from its notification. But persisted grants are capped per app, so only the newest few are
 * kept. What must hold: the grant just taken is never the one released, a document saved to twice holds one slot,
 * not two, and the list never grows past its limit whatever is stored.
 *
 * Pure common functions (`ExportGrantList.kt`), so a `kotlin.test` suite in `:shared` commonTest since CMP-4 P4c
 * (S4b-BL-28; was a JUnit test in `:app`); `ExportGrants` itself stays in `:app`.
 */
class ExportGrantsTest {

    private fun uri(n: Int) = "content://com.android.providers.downloads.documents/document/msf%3A$n"

    @Test
    fun theNewestGrantComesFirstAndNothingIsReleasedBelowTheLimit() {
        val result = retainNewestGrants(listOf(uri(2), uri(1)), uri(3), keep = 5)
        assertEquals(listOf(uri(3), uri(2), uri(1)), result.kept)
        assertTrue(result.released.isEmpty())
    }

    @Test
    fun theOldestGrantsBeyondTheLimitAreReleased() {
        val held = listOf(uri(5), uri(4), uri(3), uri(2), uri(1))
        val result = retainNewestGrants(held, uri(6), keep = 5)
        assertEquals(listOf(uri(6), uri(5), uri(4), uri(3), uri(2)), result.kept)
        assertEquals(listOf(uri(1)), result.released)
    }

    @Test
    fun aDocumentSavedToAgainMovesToTheFrontAndHoldsOneSlot() {
        // "Save to…" pointed at the file an earlier export wrote: the same URI comes back from the picker.
        val held = listOf(uri(3), uri(2), uri(1))
        val result = retainNewestGrants(held, uri(1), keep = 3)
        assertEquals(listOf(uri(1), uri(3), uri(2)), result.kept)
        // Nothing released: in particular not uri(1), which is in use again.
        assertTrue(result.released.isEmpty())
    }

    @Test
    fun theGrantJustTakenIsNeverReleasedEvenWithANonsenseLimit() {
        for (keep in listOf(0, -1)) {
            val result = retainNewestGrants(listOf(uri(1)), uri(2), keep)
            assertEquals(listOf(uri(2)), result.kept)
            assertEquals(listOf(uri(1)), result.released)
        }
    }

    @Test
    fun aStoredListLongerThanTheLimitIsTrimmedInOneStep() {
        // An older build, or a limit lowered in an update, may have stored more than the limit allows.
        val held = (10 downTo 1).map(::uri)
        val result = retainNewestGrants(held, uri(11), keep = 5)
        assertEquals((11 downTo 7).map(::uri), result.kept)
        assertEquals((6 downTo 1).map(::uri), result.released)
    }

    @Test
    fun blankEntriesAreDroppedNotKept() {
        val result = retainNewestGrants(listOf("", " ", uri(1)), uri(2), keep = 5)
        assertEquals(listOf(uri(2), uri(1)), result.kept)
        assertTrue(result.released.isEmpty())
    }

    @Test
    fun theStoredFormRoundTrips() {
        val grants = listOf(uri(3), uri(2), uri(1))
        assertEquals(grants, decodeGrants(encodeGrants(grants)))
        assertEquals(emptyList<String>(), decodeGrants(null))
        assertEquals(emptyList<String>(), decodeGrants(""))
        assertEquals(emptyList<String>(), decodeGrants(encodeGrants(emptyList())))
    }
}
