package app.doorprints.shared.sync

import app.doorprints.shared.api.ApiException
import app.doorprints.shared.api.ApiTimeoutException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncOutcomeTest {

    @Test
    fun roundTripsThroughItsStoredForm() {
        val outcome = SyncOutcome(SyncOutcome.Kind.OK, pushed = 3, pulled = 7, photosWaiting = 2)
        assertEquals("OK|3|7|2|0", outcome.encode()) // stored in DataStore: the format must not change
        assertEquals(outcome, SyncOutcome.decode(outcome.encode()))
        assertEquals(SyncOutcome(SyncOutcome.Kind.SERVER, httpCode = 503), SyncOutcome.decode("SERVER|0|0|0|503"))
        assertNull(SyncOutcome.decode("Synced: sent 1, received 2")) // v0.1 free text is ignored
        assertNull(SyncOutcome.decode("BOGUS|0|0|0|0"))
        assertNull(SyncOutcome.decode(null))
    }

    @Test
    fun classifiesErrorsWithoutServerText() {
        assertEquals(SyncOutcome.Kind.AUTH, SyncOutcome.fromError(ApiException(ApiException.Kind.AUTH, 401)).kind)
        assertEquals(SyncOutcome.Kind.CAPTIVE_PORTAL,
            SyncOutcome.fromError(ApiException(ApiException.Kind.CAPTIVE_PORTAL, 200)).kind)
        assertEquals(SyncOutcome.Kind.RATE_LIMITED, SyncOutcome.fromError(ApiException(ApiException.Kind.RATE_LIMITED, 429)).kind)
        assertEquals(503, SyncOutcome.fromError(ApiException(ApiException.Kind.SERVER, 503)).httpCode)
        assertEquals(SyncOutcome.Kind.SERVER, SyncOutcome.fromError(ApiException(ApiException.Kind.CONFLICT, 409)).kind)
        assertEquals(SyncOutcome.Kind.NETWORK, SyncOutcome.fromError(IOException("timeout")).kind)
        assertEquals(SyncOutcome.Kind.NETWORK, SyncOutcome.fromError(ApiTimeoutException(240_000)).kind)
        assertEquals(SyncOutcome.Kind.UNKNOWN, SyncOutcome.fromError(IllegalStateException()).kind)
        assertEquals(SyncOutcome.Kind.UNKNOWN, SyncOutcome.fromError(SerializationException("bad json")).kind)
    }
}
