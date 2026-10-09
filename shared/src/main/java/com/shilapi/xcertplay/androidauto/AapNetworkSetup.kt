package com.shilapi.xcertplay.androidauto

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.ExistingWifiManager
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address

/** Creates the Wi-Fi network a wireless Android Auto phone joins, with the same backends as wireless CarPlay. */
internal object AapNetworkSetup {
    /** Wi-Fi Direct group creation is slow on some head units; the others answer within seconds. */
    const val P2P_START_TIMEOUT_MILLIS = 30_000L
    const val START_TIMEOUT_MILLIS = 25_000L

    fun timeoutFor(mode: WirelessHotspotMode): Long =
        if (mode == WirelessHotspotMode.WIFI_P2P) P2P_START_TIMEOUT_MILLIS else START_TIMEOUT_MILLIS

    /**
     * In car-hotspot mode, turns the car's own hotspot on first when the driver allowed that, as
     * wireless CarPlay does. Does nothing in the other modes.
     */
    @Throws(IOException::class)
    fun prepareCarHotspot(context: Context, config: AndroidAutoRuntimeConfig, log: (String) -> Unit, isCancelled: () -> Boolean) {
        if (config.hotspotMode != WirelessHotspotMode.MANUAL) return
        if (CarHotspotSettings.shouldEnable(context, true, config.hotspotMode)) {
            val result = CarHotspotTethering.enable(
                context,
                isCancelled = { isCancelled() || !CarHotspotSettings.enabled(context) },
                timeoutMillis = WirelessStartupPolicy.HOTSPOT_READY_MILLIS,
                log = log,
            )
            // A firmware that cannot be controlled but reports no state is left to the driver.
            val leftToDriver = result == CarHotspotTethering.Result.UNSUPPORTED && CarHotspotStatus.isEnabled(context) == null
            if (result != CarHotspotTethering.Result.READY && !leftToDriver) {
                throw IOException("${result.diagnostic}. Open the car hotspot settings and connect again.")
            }
        }
        if (CarHotspotStatus.isEnabled(context) == false) {
            throw IOException("The car hotspot is off. Turn it on in the car settings and connect again.")
        }
    }

    fun createManager(
        context: Context,
        config: AndroidAutoRuntimeConfig,
        log: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): WirelessHotspotManager = when (config.hotspotMode) {
        WirelessHotspotMode.WIFI_P2P -> WifiP2pGroupManager(context, log, preferredChannel = config.wifiP2pChannel)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                LocalOnlyHotspotManager(context, log)
            } else {
                throw IOException("Android ${Build.VERSION.RELEASE} has no local-only hotspot. Choose Car hotspot or Wi-Fi Direct.")
            }
        WirelessHotspotMode.EXISTING_WIFI ->
            ExistingWifiManager(context, config.existingWifiSsid, config.existingWifiPassphrase, log)
        WirelessHotspotMode.MANUAL -> ManualHotspotManager(
            context = context,
            ssid = config.manualSsid.ifBlank { throw IOException("The car hotspot name is not set") },
            passphrase = config.manualPassphrase,
            band = config.manualBand,
            channel = config.manualChannel,
            security = config.manualSecurity,
            onDiagnostic = log,
            isCancelled = isCancelled,
        )
    }

    /** Converts what the hotspot manager reports into the bootstrap message the phone needs. */
    fun toNetwork(info: WirelessHotspotInfo, log: (String) -> Unit = {}): AapWifiNetwork {
        val host = (info.hostAddresses + listOfNotNull(info.hostAddress))
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?: throw IOException("The Wi-Fi network has no IPv4 address; Android Auto needs one")
        val bssid = normalizeBssid(info.bssid) ?: info.accessPointBssid?.takeIf { it.size == 6 }?.let(::formatBssid)
        if (bssid == null) log("The access point address is unknown; the phone may not find the network")
        val security = when (info.security) {
            Iap2WirelessSecurity.NONE -> AapWifiNetwork.Security.OPEN
            Iap2WirelessSecurity.WPA_WPA2, Iap2WirelessSecurity.WPA3_TRANSITION -> AapWifiNetwork.Security.WPA2_PERSONAL
            Iap2WirelessSecurity.WEP, Iap2WirelessSecurity.WPA3_ONLY -> {
                log("Security ${info.security} has no Android Auto equivalent; announcing WPA2")
                AapWifiNetwork.Security.WPA2_PERSONAL
            }
        }
        return AapWifiNetwork(
            ssid = info.ssid,
            passphrase = info.passphrase,
            bssid = bssid ?: UNKNOWN_BSSID,
            security = security,
            dynamic = info.backend == WirelessHotspotBackend.WIFI_P2P || info.backend == WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT,
            hostAddress = host.hostAddress!!,
        )
    }

    private const val UNKNOWN_BSSID = "00:00:00:00:00:00"

    internal fun normalizeBssid(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = text.split(':', '-')
        if (parts.size != 6 || parts.any { it.length != 2 || it.any { c -> c !in "0123456789abcdefABCDEF" } }) return null
        return parts.joinToString(":") { it.uppercase() }
    }

    private fun formatBssid(bytes: ByteArray): String = bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
}
