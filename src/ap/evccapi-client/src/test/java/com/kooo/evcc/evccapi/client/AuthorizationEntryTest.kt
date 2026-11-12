package com.kooo.evcc.evccapi.client

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.kooo.evcc.evccapi.EvccApiContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, application = android.app.Application::class)
class AuthorizationEntryTest {
    private class RecordingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var started: Intent? = null
        var unavailable = false
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example.client"
        override fun startActivity(intent: Intent) {
            if (unavailable) throw ActivityNotFoundException("unsupported entry")
            started = intent
        }
    }

    @Test fun authorizationUsesTheActualClientPackageWithoutAConnectionOrPreset() {
        val context = RecordingContext()
        EvccApiClient(context, clientPackage = "com.other.claimed").use { client ->
            assertTrue(client.openAuthorization())
            val intent = context.started!!
            assertEquals(EvccApiContract.ACTION_OPEN_API_AUTHORIZATION, intent.action)
            assertEquals(EvccApiContract.EVCC_PACKAGE, intent.`package`)
            assertEquals("com.example.client", intent.getStringExtra(EvccApiContract.EXTRA_PACKAGE_NAME))
            assertEquals(setOf(EvccApiContract.EXTRA_PACKAGE_NAME), intent.extras!!.keySet())
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertEquals(EvccApiClient.ConnectionState.DISCONNECTED, client.connectionState())
            assertTrue(client.openSettings())
            assertEquals(EvccApiContract.ACTION_OPEN_SETTINGS, context.started!!.action)
            assertFalse(context.started!!.hasExtra(EvccApiContract.EXTRA_PACKAGE_NAME))
        }
    }

    @Test fun unsupportedAuthorizationCanFallBackWithoutStartingASession() {
        val context = RecordingContext().apply { unavailable = true }
        var operation: String? = null
        EvccApiClient(context, listener = object : EvccApiClient.Listener {
            override fun onError(operationName: String, error: Throwable) { operation = operationName }
        }).use { client ->
            assertFalse(client.openAuthorization())
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("openAuthorization", operation)
            assertNull(client.currentSession())
            context.unavailable = false
            assertTrue(client.openSettings())
        }
    }
}
