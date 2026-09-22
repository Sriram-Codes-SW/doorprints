package com.househunt.app

import com.househunt.app.data.ApiException
import com.househunt.app.data.SyncOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class SyncOutcomeTest {

    @Test
    fun roundTripsThroughItsStoredForm() {
        val outcome = SyncOutcome(SyncOutcome.Kind.OK, pushed = 3, pulled = 7, photosWaiting = 2)
        assertEquals(outcome, SyncOutcome.decode(outcome.encode()))
        assertNull(SyncOutcome.decode("Synced: sent 1, received 2")) // v0.1 free text is ignored
        assertNull(SyncOutcome.decode(null))
    }

    @Test
    fun classifiesErrorsWithoutServerText() {
        assertEquals(SyncOutcome.Kind.AUTH, SyncOutcome.fromError(ApiException(ApiException.Kind.AUTH, 401)).kind)
        assertEquals(SyncOutcome.Kind.CAPTIVE_PORTAL,
            SyncOutcome.fromError(ApiException(ApiException.Kind.CAPTIVE_PORTAL, 200)).kind)
        assertEquals(SyncOutcome.Kind.RATE_LIMITED, SyncOutcome.fromError(ApiException(ApiException.Kind.RATE_LIMITED, 429)).kind)
        assertEquals(503, SyncOutcome.fromError(ApiException(ApiException.Kind.SERVER, 503)).httpCode)
        assertEquals(SyncOutcome.Kind.NETWORK, SyncOutcome.fromError(IOException("timeout")).kind)
        assertEquals(SyncOutcome.Kind.UNKNOWN, SyncOutcome.fromError(IllegalStateException()).kind)
    }
}
