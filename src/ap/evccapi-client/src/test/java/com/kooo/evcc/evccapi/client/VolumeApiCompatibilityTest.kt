package com.kooo.evcc.evccapi.client

import android.os.DeadObjectException
import com.kooo.evcc.evccapi.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VolumeApiCompatibilityTest {
    private fun client(minor: Int, call: (String) -> Any?): EvccApiClient {
        val client = EvccApiClient(RuntimeEnvironment.getApplication())
        val session = Proxy.newProxyInstance(IEvccApiSession::class.java.classLoader,
            arrayOf(IEvccApiSession::class.java)) { _, method, _ -> call(method.name) } as IEvccApiSession
        EvccApiClient::class.java.getDeclaredField("session").apply { isAccessible = true }.set(client, session)
        EvccApiClient::class.java.getDeclaredField("apiInfo").apply { isAccessible = true }.set(client, EvccApiInfo(apiMinor = minor))
        return client
    }

    @Test fun olderServicesReceiveNoNewVolumeTransactions() {
        for (minor in listOf(0, 1)) {
            var calls = 0
            client(minor) { calls++; error("Unexpected transaction $it") }.use { sdk ->
                assertEquals("UNSUPPORTED", sdk.queryVolumeGroups().status)
                assertEquals("UNSUPPORTED", sdk.readVolumeGroup(3).status)
                assertEquals("UNSUPPORTED", sdk.setVolumeGroup(EvccVolumeSetRequest("s", 3, 10)).status)
                assertEquals("UNSUPPORTED", sdk.adjustVolumeGroup(EvccVolumeAdjustRequest("a", 3, 1)).status)
                assertEquals(0, calls)
            }
        }
    }

    @Test fun newServiceReceivesEachWriteExactlyOnceEvenWhenTransportFails() {
        var calls = 0
        client(2) { calls++; throw DeadObjectException() }.use { sdk ->
            try { sdk.adjustVolumeGroup(EvccVolumeAdjustRequest("a", 3, 1)); fail("Expected transport failure") }
            catch (_: DeadObjectException) { }
            assertEquals(1, calls)
        }
        assertFalse(supportsVolumeApi(2, 2))
        assertTrue(supportsVolumeApi(1, 3))
    }
}
