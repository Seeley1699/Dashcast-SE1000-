package com.kooo.evcc.evccapi.client

import com.kooo.evcc.evccapi.EvccApiContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRetryPolicyTest {
    @Test fun authorizationAndServiceShutdownRequireUserAction() {
        assertFalse(ConnectionRetryPolicy.shouldRetryAfterSessionClosed(EvccApiContract.Status.PERMISSION_DENIED))
        assertFalse(ConnectionRetryPolicy.shouldRetryAfterSessionClosed(EvccApiContract.Status.SERVICE_DISABLED))
    }

    @Test fun binderStyleSessionClosureCanReconnect() {
        assertTrue(ConnectionRetryPolicy.shouldRetryAfterSessionClosed(EvccApiContract.Status.SESSION_CLOSED))
        assertTrue(ConnectionRetryPolicy.shouldRetryAfterSessionClosed(EvccApiContract.Status.FAILED))
    }
}
