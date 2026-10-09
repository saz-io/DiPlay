package com.shilapi.xcertplay.androidauto

import android.content.Intent
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * Everything the receiver needs from the app that hosts it, passed to its service in an Intent.
 * The Wi-Fi fields mirror the choices the driver already made for wireless CarPlay.
 */
class AndroidAutoRuntimeConfig(
    val hotspotMode: WirelessHotspotMode,
    val wifiP2pChannel: Int = WifiP2pChannels.AUTO,
    val existingWifiSsid: String = "",
    val existingWifiPassphrase: String = "",
    val manualSsid: String = "",
    val manualPassphrase: String = "",
    val manualBand: ManualHotspotBand = ManualHotspotBand.AUTO,
    val manualChannel: Int = 0,
    val manualSecurity: ManualHotspotSecurity = ManualHotspotSecurity.WPA2,
    val framesPerSecond: Int = 30,
    /** True for left-hand drive, the usual layout. */
    val leftHandDrive: Boolean = true,
    val texts: Texts,
    /** Drawable resource of the host app's notification icon. */
    val notificationIcon: Int,
) {
    /** User-facing strings, supplied by the host app so they follow its language. */
    class Texts(
        val title: String,
        val waiting: String,
        val connected: String,
        val disconnect: String,
    )

    init {
        require(WifiP2pChannels.isValid(wifiP2pChannel)) { "Unsupported Wi-Fi Direct channel: $wifiP2pChannel" }
        require(framesPerSecond == 30 || framesPerSecond == 60) { "Frame rate must be 30 or 60" }
    }

    fun writeTo(intent: Intent): Intent {
        for ((key, value) in toExtras()) {
            when (value) {
                is String -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
            }
        }
        return intent
    }

    /** The values [writeTo] puts into an Intent, as strings, ints and booleans. */
    internal fun toExtras(): Map<String, Any> = mapOf(
        KEY_MODE to hotspotMode.name,
        KEY_P2P_CHANNEL to wifiP2pChannel,
        KEY_EXISTING_SSID to existingWifiSsid,
        KEY_EXISTING_PASSPHRASE to existingWifiPassphrase,
        KEY_MANUAL_SSID to manualSsid,
        KEY_MANUAL_PASSPHRASE to manualPassphrase,
        KEY_MANUAL_BAND to manualBand.name,
        KEY_MANUAL_CHANNEL to manualChannel,
        KEY_MANUAL_SECURITY to manualSecurity.name,
        KEY_FPS to framesPerSecond,
        KEY_LEFT_HAND_DRIVE to leftHandDrive,
        KEY_TITLE to texts.title,
        KEY_WAITING to texts.waiting,
        KEY_CONNECTED to texts.connected,
        KEY_DISCONNECT to texts.disconnect,
        KEY_ICON to notificationIcon,
    )

    // The passphrases are Wi-Fi credentials and never appear in logs.
    override fun toString(): String = "AndroidAutoRuntimeConfig(mode=$hotspotMode, fps=$framesPerSecond)"

    companion object {
        private const val KEY_MODE = "aa.mode"
        private const val KEY_P2P_CHANNEL = "aa.p2pChannel"
        private const val KEY_EXISTING_SSID = "aa.existingSsid"
        private const val KEY_EXISTING_PASSPHRASE = "aa.existingPassphrase"
        private const val KEY_MANUAL_SSID = "aa.manualSsid"
        private const val KEY_MANUAL_PASSPHRASE = "aa.manualPassphrase"
        private const val KEY_MANUAL_BAND = "aa.manualBand"
        private const val KEY_MANUAL_CHANNEL = "aa.manualChannel"
        private const val KEY_MANUAL_SECURITY = "aa.manualSecurity"
        private const val KEY_FPS = "aa.fps"
        private const val KEY_LEFT_HAND_DRIVE = "aa.leftHandDrive"
        private const val KEY_TITLE = "aa.title"
        private const val KEY_WAITING = "aa.waiting"
        private const val KEY_CONNECTED = "aa.connected"
        private const val KEY_DISCONNECT = "aa.disconnect"
        private const val KEY_ICON = "aa.icon"

        /** Reads a config written by [writeTo], or null when the Intent does not carry a valid one. */
        fun readFrom(intent: Intent): AndroidAutoRuntimeConfig? = readFrom(object : Extras {
            override fun string(key: String): String? = intent.getStringExtra(key)
            override fun int(key: String, default: Int): Int = intent.getIntExtra(key, default)
            override fun boolean(key: String, default: Boolean): Boolean = intent.getBooleanExtra(key, default)
        })

        internal fun readFrom(extras: Extras): AndroidAutoRuntimeConfig? {
            val mode = extras.string(KEY_MODE) ?: return null
            val title = extras.string(KEY_TITLE) ?: return null
            val waiting = extras.string(KEY_WAITING) ?: return null
            val connected = extras.string(KEY_CONNECTED) ?: return null
            val disconnect = extras.string(KEY_DISCONNECT) ?: return null
            return try {
                AndroidAutoRuntimeConfig(
                    hotspotMode = WirelessHotspotMode.valueOf(mode),
                    wifiP2pChannel = extras.int(KEY_P2P_CHANNEL, WifiP2pChannels.AUTO),
                    existingWifiSsid = extras.string(KEY_EXISTING_SSID).orEmpty(),
                    existingWifiPassphrase = extras.string(KEY_EXISTING_PASSPHRASE).orEmpty(),
                    manualSsid = extras.string(KEY_MANUAL_SSID).orEmpty(),
                    manualPassphrase = extras.string(KEY_MANUAL_PASSPHRASE).orEmpty(),
                    manualBand = ManualHotspotBand.valueOf(extras.string(KEY_MANUAL_BAND) ?: ManualHotspotBand.AUTO.name),
                    manualChannel = extras.int(KEY_MANUAL_CHANNEL, 0),
                    manualSecurity = ManualHotspotSecurity.valueOf(
                        extras.string(KEY_MANUAL_SECURITY) ?: ManualHotspotSecurity.WPA2.name,
                    ),
                    framesPerSecond = extras.int(KEY_FPS, 30),
                    leftHandDrive = extras.boolean(KEY_LEFT_HAND_DRIVE, true),
                    texts = Texts(title, waiting, connected, disconnect),
                    notificationIcon = extras.int(KEY_ICON, 0),
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    /** The slice of an Intent's extras this config needs, so it can be read without Android. */
    internal interface Extras {
        fun string(key: String): String?

        fun int(key: String, default: Int): Int

        fun boolean(key: String, default: Boolean): Boolean
    }
}
