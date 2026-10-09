package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.androidauto.AndroidAutoFailure
import com.shilapi.xcertplay.androidauto.AndroidAutoGate
import com.shilapi.xcertplay.androidauto.AndroidAutoIdentityStatus
import com.shilapi.xcertplay.androidauto.AndroidAutoLauncher
import com.shilapi.xcertplay.androidauto.AndroidAutoPhase
import com.shilapi.xcertplay.androidauto.AndroidAutoRuntimeConfig
import com.shilapi.xcertplay.androidauto.AndroidAutoSettings
import com.shilapi.xcertplay.androidauto.AndroidAutoSnapshot
import com.shilapi.xcertplay.androidauto.AndroidAutoState
import com.shilapi.xcertplay.host.R

/**
 * Connects the experimental Android Auto receiver in `:shared` to DiPlay's settings, its strings
 * and its CarPlay session. Everything that needs the app's resources lives here; the receiver
 * itself has none.
 */
internal object AndroidAutoSupport {
    private const val TAG = "DiPlay-AndroidAuto"

    /** Android Auto and CarPlay share the Wi-Fi network and Bluetooth, so only one runs at a time. */
    fun installGate() {
        AndroidAutoGate.otherProjectionRunning = { CarPlayBackgroundSession.hasSession() }
    }

    /** The receiver uses the Wi-Fi mode and the vehicle settings that wireless CarPlay already uses. */
    fun runtimeConfig(context: Context): AndroidAutoRuntimeConfig = AndroidAutoRuntimeConfig(
        hotspotMode = AirPlayPersistence.loadWirelessHotspotMode(context),
        wifiP2pChannel = AirPlayPersistence.loadWifiP2pPreferredChannel(context),
        existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(context),
        existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(context),
        manualSsid = AirPlayPersistence.loadManualHotspotSsid(context),
        manualPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(context),
        manualBand = AirPlayPersistence.loadManualHotspotBand(context),
        manualChannel = AirPlayPersistence.loadManualHotspotChannel(context),
        manualSecurity = AirPlayPersistence.loadManualHotspotSecurity(context),
        framesPerSecond = if (AirPlayPersistence.loadFps(context) >= 60) 60 else 30,
        leftHandDrive = !AirPlayPersistence.loadRightHandDrive(context),
        texts = AndroidAutoRuntimeConfig.Texts(
            title = context.getString(R.string.settings_android_auto_notification_title),
            waiting = context.getString(R.string.settings_android_auto_notification_waiting),
            connected = context.getString(R.string.settings_android_auto_notification_connected),
            disconnect = context.getString(R.string.settings_android_auto_notification_disconnect),
        ),
        notificationIcon = R.drawable.ic_diplay_notification,
    )

    fun requiredPermissions(context: Context): List<String> =
        WirelessPermissions.required(AirPlayPersistence.loadWirelessHotspotMode(context), Build.VERSION.SDK_INT)

    fun missingPermissions(context: Context): List<String> =
        requiredPermissions(context).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** Starts listening for a phone. The caller has checked [missingPermissions]. */
    fun start(context: Context) {
        try {
            AndroidAutoLauncher.start(context, runtimeConfig(context))
        } catch (error: RuntimeException) {
            // Android refuses foreground services started from the background on some versions.
            Log.w(TAG, "The receiver could not be started: ${error.javaClass.simpleName}")
        }
    }

    fun stop(context: Context) = AndroidAutoLauncher.stop(context)

    /** Called when DiPlay opens: resumes listening if the driver left the receiver on. */
    fun resumeIfEnabled(context: Context) {
        if (!AndroidAutoSettings.enabled(context)) return
        if (AndroidAutoState.snapshot.phase != AndroidAutoPhase.OFF) return
        if (missingPermissions(context).isNotEmpty()) return
        start(context)
    }

    fun identityValue(context: Context, status: AndroidAutoIdentityStatus): String = context.getString(
        when (status) {
            is AndroidAutoIdentityStatus.NotImported -> R.string.settings_android_auto_identity_missing
            is AndroidAutoIdentityStatus.Ready -> R.string.settings_android_auto_identity_ready
            is AndroidAutoIdentityStatus.Expired -> R.string.settings_android_auto_identity_expired
            is AndroidAutoIdentityStatus.Unusable -> R.string.settings_android_auto_identity_unusable
        },
    )

    fun statusText(context: Context, snapshot: AndroidAutoSnapshot): String {
        val state = when (snapshot.phase) {
            AndroidAutoPhase.OFF -> context.getString(R.string.settings_android_auto_state_off)
            AndroidAutoPhase.LISTENING -> context.getString(R.string.settings_android_auto_state_listening)
            AndroidAutoPhase.PREPARING_NETWORK -> context.getString(R.string.settings_android_auto_state_network)
            AndroidAutoPhase.WAITING_FOR_PHONE -> context.getString(R.string.settings_android_auto_state_phone_wifi)
            AndroidAutoPhase.CONNECTING -> context.getString(R.string.settings_android_auto_state_connecting)
            AndroidAutoPhase.PROJECTING -> snapshot.phoneName
                ?.let { context.getString(R.string.settings_android_auto_state_projecting_named, it) }
                ?: context.getString(R.string.settings_android_auto_state_projecting)
            AndroidAutoPhase.ERROR -> context.getString(failureText(snapshot.failure))
        }
        return context.getString(R.string.settings_android_auto_status, state)
    }

    private fun failureText(failure: AndroidAutoFailure?): Int = when (failure) {
        AndroidAutoFailure.OTHER_PROJECTION_RUNNING -> R.string.settings_android_auto_failure_busy
        AndroidAutoFailure.NO_IDENTITY -> R.string.settings_android_auto_failure_no_identity
        AndroidAutoFailure.IDENTITY_UNUSABLE -> R.string.settings_android_auto_failure_identity_unusable
        AndroidAutoFailure.BLUETOOTH_OFF -> R.string.settings_android_auto_failure_bluetooth_off
        AndroidAutoFailure.BLUETOOTH_PERMISSION -> R.string.settings_android_auto_failure_bluetooth_permission
        AndroidAutoFailure.NETWORK_FAILED -> R.string.settings_android_auto_failure_network
        AndroidAutoFailure.PHONE_DID_NOT_JOIN -> R.string.settings_android_auto_failure_phone_wifi
        AndroidAutoFailure.INCOMPATIBLE_PHONE -> R.string.settings_android_auto_failure_incompatible
        AndroidAutoFailure.LINK_LOST -> R.string.settings_android_auto_failure_link_lost
        AndroidAutoFailure.PROTOCOL_ERROR -> R.string.settings_android_auto_failure_protocol
        AndroidAutoFailure.UNKNOWN, null -> R.string.settings_android_auto_failure_unknown
    }
}
