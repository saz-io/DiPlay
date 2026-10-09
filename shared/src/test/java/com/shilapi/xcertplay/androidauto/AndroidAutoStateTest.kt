package com.shilapi.xcertplay.androidauto

import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidAutoStateTest {
    @After fun reset() {
        AndroidAutoState.update(AndroidAutoPhase.OFF)
        AndroidAutoGate.otherProjectionRunning = { false }
    }

    @Test fun listenersSeeEveryChangeUntilTheyAreRemoved() {
        val seen = ArrayList<AndroidAutoSnapshot>()
        val remove = AndroidAutoState.addListener { seen += it }
        AndroidAutoState.update(AndroidAutoPhase.LISTENING)
        AndroidAutoState.update(AndroidAutoPhase.ERROR, failure = AndroidAutoFailure.NETWORK_FAILED, detail = "x")
        remove()
        AndroidAutoState.update(AndroidAutoPhase.PROJECTING, phoneName = "Pixel")
        assertEquals(listOf(AndroidAutoPhase.LISTENING, AndroidAutoPhase.ERROR), seen.map { it.phase })
        assertEquals(AndroidAutoFailure.NETWORK_FAILED, seen[1].failure)
        assertEquals("Pixel", AndroidAutoState.snapshot.phoneName)
    }

    @Test fun aNewPhaseClearsTheEarlierFailure() {
        AndroidAutoState.update(AndroidAutoPhase.ERROR, failure = AndroidAutoFailure.LINK_LOST)
        AndroidAutoState.update(AndroidAutoPhase.LISTENING)
        assertNull(AndroidAutoState.snapshot.failure)
    }

    @Test fun theGateDefaultsToOpenAndFollowsTheHost() {
        assertFalse(AndroidAutoGate.otherProjectionRunning())
        AndroidAutoGate.otherProjectionRunning = { true }
        assertTrue(AndroidAutoGate.otherProjectionRunning())
    }

    @Test fun leaveRequestsReachRegisteredListenersOnly() {
        var count = 0
        val remove = AndroidAutoRuntime.addLeaveListener { count++ }
        AndroidAutoRuntime.requestLeave()
        remove()
        AndroidAutoRuntime.requestLeave()
        assertEquals(1, count)
    }

    private fun config() = AndroidAutoRuntimeConfig(
        hotspotMode = WirelessHotspotMode.MANUAL,
        wifiP2pChannel = WifiP2pChannels.AUTO,
        existingWifiSsid = "home", existingWifiPassphrase = "home-pass",
        manualSsid = "car", manualPassphrase = "car-pass", manualBand = ManualHotspotBand.GHZ_5, manualChannel = 36,
        manualSecurity = ManualHotspotSecurity.WPA3_TRANSITION, framesPerSecond = 60, leftHandDrive = false,
        texts = AndroidAutoRuntimeConfig.Texts("Title", "Waiting", "Connected", "Disconnect"), notificationIcon = 42,
    )

    private fun extras(map: Map<String, Any>) = object : AndroidAutoRuntimeConfig.Extras {
        override fun string(key: String) = map[key] as? String
        override fun int(key: String, default: Int) = map[key] as? Int ?: default
        override fun boolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    }

    @Test fun configSurvivesTheTripThroughIntentExtras() {
        val original = config()
        val read = AndroidAutoRuntimeConfig.readFrom(extras(original.toExtras()))!!
        assertEquals(original.toExtras(), read.toExtras())
        assertEquals(WirelessHotspotMode.MANUAL, read.hotspotMode)
        assertEquals(ManualHotspotBand.GHZ_5, read.manualBand)
        assertEquals(60, read.framesPerSecond)
        assertFalse(read.leftHandDrive)
        assertEquals("Connected", read.texts.connected)
    }

    @Test fun incompleteOrInvalidExtrasAreRefused() {
        val complete = config().toExtras()
        for (missing in listOf("aa.mode", "aa.title", "aa.waiting", "aa.connected", "aa.disconnect")) {
            assertNull(missing, AndroidAutoRuntimeConfig.readFrom(extras(complete - missing)))
        }
        assertNull(AndroidAutoRuntimeConfig.readFrom(extras(complete + ("aa.mode" to "NOPE"))))
        assertNull(AndroidAutoRuntimeConfig.readFrom(extras(complete + ("aa.fps" to 24))))
        assertNull(AndroidAutoRuntimeConfig.readFrom(extras(complete + ("aa.manualBand" to "NOPE"))))
        assertNotNull(AndroidAutoRuntimeConfig.readFrom(extras(complete)))
    }

    @Test fun credentialsNeverAppearInTheTextForm() {
        val text = config().toString()
        assertFalse(text.contains("car-pass"))
        assertFalse(text.contains("home-pass"))
    }

    @Test fun invalidConstructionIsRejected() {
        try {
            AndroidAutoRuntimeConfig(WirelessHotspotMode.MANUAL, framesPerSecond = 45,
                texts = AndroidAutoRuntimeConfig.Texts("a", "b", "c", "d"), notificationIcon = 0)
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun runtimeHoldsNothingUntilASessionStarts() {
        assertNull(AndroidAutoRuntime.active)
        assertSame(AndroidAutoRuntime.active, null)
    }
}
