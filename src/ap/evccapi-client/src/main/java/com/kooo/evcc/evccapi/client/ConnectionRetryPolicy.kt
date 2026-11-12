package com.kooo.evcc.evccapi.client

import com.kooo.evcc.evccapi.EvccApiContract

/** Explicit authorization/service shutdowns require user action instead of an endless bind loop. */
internal object ConnectionRetryPolicy {
    fun shouldRetryAfterSessionClosed(status: String): Boolean =
        status != EvccApiContract.Status.PERMISSION_DENIED &&
            status != EvccApiContract.Status.SERVICE_DISABLED
}
