package com.shilapi.xcertplay.androidauto

import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AapNetworkSetupTest {
    private fun info(
        host: String? = "192.168.49.1",
        bssid: String? = "aa:bb:cc:dd:ee:ff",
        security: Iap2WirelessSecurity = Iap2WirelessSecurity.WPA_WPA2,
        backend: WirelessHotspotBackend = WirelessHotspotBackend.WIFI_P2P,
        accessPoint: ByteArray? = null,
        extraHosts: List<String> = emptyList(),
    ) = WirelessHotspotInfo(
        ssid = "DIRECT-xy-DiPlay", passphrase = "secret-pass", security = security, channel = 36, frequencyMHz = 5180,
        bssid = bssid, interfaceName = "p2p-wlan0-0", hostAddress = host?.let(InetAddress::getByName), bandLabel = "5 GHz",
        backend = backend,
        hostAddresses = (listOfNotNull(host) + extraHosts).map(InetAddress::getByName),
        accessPointBssid = accessPoint,
    )

    @Test fun mapsAWifiDirectGroupToTheNetworkTheBluetoothMessageNeeds() {
        val network = AapNetworkSetup.toNetwork(info())
        assertEquals("DIRECT-xy-DiPlay", network.ssid)
        assertEquals("secret-pass", network.passphrase)
        assertEquals("AA:BB:CC:DD:EE:FF", network.bssid)
        assertEquals(AapWifiNetwork.Security.WPA2_PERSONAL, network.security)
        assertEquals("192.168.49.1", network.hostAddress)
        assertTrue("a Wi-Fi Direct group changes between sessions", network.dynamic)
    }

    @Test fun fixedNetworksAreAnnouncedAsStatic() {
        assertEquals(false, AapNetworkSetup.toNetwork(info(backend = WirelessHotspotBackend.MANUAL_HOTSPOT)).dynamic)
        assertEquals(false, AapNetworkSetup.toNetwork(info(backend = WirelessHotspotBackend.EXISTING_WIFI)).dynamic)
        assertEquals(true, AapNetworkSetup.toNetwork(info(backend = WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT)).dynamic)
    }

    @Test fun prefersAnIpv4AddressWhenTheInterfaceHasSeveral() {
        val network = AapNetworkSetup.toNetwork(info(host = "fe80::1", extraHosts = listOf("192.168.43.1")))
        assertEquals("192.168.43.1", network.hostAddress)
    }

    @Test fun aNetworkWithoutIpv4CannotBeUsed() {
        for (build in listOf({ info(host = null) }, { info(host = "fe80::1") })) {
            try {
                AapNetworkSetup.toNetwork(build())
                fail()
            } catch (_: IOException) {
            }
        }
    }

    @Test fun fallsBackToTheAccessPointAddressWhenTheBssidIsMissingOrMalformed() {
        val fromBytes = AapNetworkSetup.toNetwork(info(bssid = null, accessPoint = byteArrayOf(2, 0x11, 0x22, 0x33, 0x44, 0x55)))
        assertEquals("02:11:22:33:44:55", fromBytes.bssid)
        val malformed = AapNetworkSetup.toNetwork(info(bssid = "garbage", accessPoint = byteArrayOf(2, 0x11, 0x22, 0x33, 0x44, 0x55)))
        assertEquals("02:11:22:33:44:55", malformed.bssid)
    }

    @Test fun anUnknownBssidIsLoggedAndZeroed() {
        val logs = ArrayList<String>()
        val network = AapNetworkSetup.toNetwork(info(bssid = null), logs::add)
        assertEquals("00:00:00:00:00:00", network.bssid)
        assertTrue(logs.any { it.contains("unknown") })
    }

    @Test fun securityIsMappedToWhatAndroidAutoUnderstands() {
        val logs = ArrayList<String>()
        assertEquals(AapWifiNetwork.Security.OPEN, AapNetworkSetup.toNetwork(info(security = Iap2WirelessSecurity.NONE)).security)
        assertEquals(AapWifiNetwork.Security.WPA2_PERSONAL, AapNetworkSetup.toNetwork(info(security = Iap2WirelessSecurity.WPA3_TRANSITION)).security)
        assertEquals(AapWifiNetwork.Security.WPA2_PERSONAL, AapNetworkSetup.toNetwork(info(security = Iap2WirelessSecurity.WPA3_ONLY), logs::add).security)
        assertTrue("an unsupported mode is reported, not hidden", logs.any { it.contains("WPA3_ONLY") })
    }

    @Test fun normalizesBssidSpellings() {
        assertEquals("AA:BB:CC:DD:EE:FF", AapNetworkSetup.normalizeBssid(" aa-bb-cc-dd-ee-ff "))
        assertEquals("AA:BB:CC:DD:EE:FF", AapNetworkSetup.normalizeBssid("AA:BB:CC:DD:EE:FF"))
        for (bad in listOf(null, "", "aa:bb", "aa:bb:cc:dd:ee:gg", "aabbccddeeff", "a:b:c:d:e:f")) assertNull(bad, AapNetworkSetup.normalizeBssid(bad))
    }

    @Test fun waitingTimeIsLongerForWifiDirect() {
        assertTrue(AapNetworkSetup.timeoutFor(WirelessHotspotMode.WIFI_P2P) > AapNetworkSetup.timeoutFor(WirelessHotspotMode.MANUAL))
    }
}
