package com.shilapi.xcertplay.androidauto

import android.bluetooth.BluetoothSocket
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.shilapi.xcertplay.network.WirelessHotspotManager
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicReference

/**
 * One phone, from the moment it opens the Bluetooth service until its projection ends.
 *
 * It creates the Wi-Fi network, tells the phone over Bluetooth where to connect, accepts the
 * phone's TCP connection and then runs the projection session. [run] blocks and always releases
 * every resource it acquired.
 */
internal class AndroidAutoConnection(
    private val context: Context,
    private val config: AndroidAutoRuntimeConfig,
    private val identityStore: AndroidAutoIdentityStore,
) {
    @Volatile private var cancelled = false
    private val closeOnCancel = AtomicReference<List<Closeable>>(emptyList())

    /** Aborts a connection that is still being set up, or ends a running session. */
    fun cancel() {
        cancelled = true
        closeOnCancel.get().forEach(::closeQuietly)
        AndroidAutoRuntime.active?.session?.close()
    }

    fun run(bluetooth: BluetoothSocket) {
        if (AndroidAutoGate.otherProjectionRunning()) {
            log("Another projection is running; ignoring the phone")
            AndroidAutoState.update(AndroidAutoPhase.ERROR, failure = AndroidAutoFailure.OTHER_PROJECTION_RUNNING)
            return
        }
        val identity = identityStore.load()
        if (identity == null) {
            AndroidAutoState.update(AndroidAutoPhase.ERROR, failure = AndroidAutoFailure.NO_IDENTITY)
            return
        }

        var manager: WirelessHotspotManager? = null
        var server: ServerSocket? = null
        var tcp: Socket? = null
        var decoder: AapVideoDecoder? = null
        var stage = Stage.NETWORK
        try {
            AndroidAutoState.update(AndroidAutoPhase.PREPARING_NETWORK)
            AapNetworkSetup.prepareCarHotspot(context, config, ::log) { cancelled }
            val created = AapNetworkSetup.createManager(context, config, ::log) { cancelled }
            manager = created
            closeOnCancel.set(listOf(created))
            val info = created.start(AapNetworkSetup.timeoutFor(config.hotspotMode))
            val network = AapNetworkSetup.toNetwork(info, ::log)
            log("Wi-Fi network ready at ${network.hostAddress}")

            val listener = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(AapWireless.TCP_PORT))
                soTimeout = ACCEPT_TIMEOUT_MILLIS
            }
            server = listener
            closeOnCancel.set(listOf(created, listener, bluetooth))

            stage = Stage.PHONE_JOINING
            AndroidAutoState.update(AndroidAutoPhase.WAITING_FOR_PHONE)
            val handshakeFailure = AtomicReference<String?>()
            val handshake = Thread({
                try {
                    val outcome = AapWirelessHandshake(bluetooth.inputStream, bluetooth.outputStream, ::log).run(network)
                    when (outcome) {
                        AapWifiOutcome.PHONE_REFUSED -> handshakeFailure.set("The phone could not join the car's Wi-Fi network")
                        AapWifiOutcome.LINK_ENDED_EARLY -> handshakeFailure.set("Bluetooth ended before the phone answered")
                        AapWifiOutcome.PHONE_JOINED, AapWifiOutcome.PHONE_ACCEPTED -> Unit
                    }
                } catch (error: IOException) {
                    handshakeFailure.set("Bluetooth failed: ${error.message.orEmpty()}")
                }
                // A failed bootstrap means no TCP connection is coming; stop waiting for one.
                if (handshakeFailure.get() != null) closeQuietly(listener)
            }, "aap-bluetooth-handshake").apply {
                isDaemon = true
                start()
            }

            val accepted = try {
                listener.accept()
            } catch (_: SocketTimeoutException) {
                throw IOException(handshakeFailure.get() ?: "The phone did not connect to the car's Wi-Fi network in time")
            } catch (error: SocketException) {
                throw IOException(handshakeFailure.get() ?: "Waiting for the phone ended")
            }
            tcp = accepted
            closeOnCancel.set(listOf(created, accepted))
            accepted.tcpNoDelay = true
            accepted.keepAlive = true
            closeQuietly(listener)
            closeQuietly(bluetooth)
            handshake.interrupt()
            log("Phone connected over Wi-Fi")

            stage = Stage.SESSION
            AndroidAutoState.update(AndroidAutoPhase.CONNECTING)
            val metrics = displayMetrics()
            val video = AapDisplayPlanner.plan(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, config.framesPerSecond)
            val videoDecoder = AapVideoDecoder(video.resolution.width, video.resolution.height, ::log)
            decoder = videoDecoder
            val host = AndroidAutoHost(context, videoDecoder, ::log)
            val headUnit = AapHeadUnitConfig(
                info = AapHeadUnitInfo(driverPosition = if (config.leftHandDrive) 0 else 1),
                video = video,
            )
            val session = AapSession(accepted.getInputStream(), accepted.getOutputStream(), AapTlsClient(identity), headUnit, host)
            session.attach(accepted)
            AndroidAutoRuntime.active = AndroidAutoRuntime.Active(session, videoDecoder, video)
            closeOnCancel.set(listOf(created, accepted))
            if (cancelled) return

            val reason = session.run()
            log("Projection ended: $reason")
            val failure = failureFor(reason)
            if (failure == null) {
                AndroidAutoState.update(AndroidAutoPhase.LISTENING)
            } else {
                AndroidAutoState.update(AndroidAutoPhase.ERROR, failure = failure, detail = reason.name)
            }
        } catch (error: GeneralSecurityException) {
            log("TLS setup failed: ${error.javaClass.simpleName}")
            AndroidAutoState.update(AndroidAutoPhase.ERROR, failure = AndroidAutoFailure.IDENTITY_UNUSABLE)
        } catch (error: IOException) {
            fail(stage, error)
        } catch (error: RuntimeException) {
            // Hotspot managers report unusable states with unchecked exceptions.
            fail(stage, error)
        } finally {
            closeOnCancel.set(emptyList())
            AndroidAutoRuntime.active = null
            decoder?.release()
            tcp?.let(::closeQuietly)
            server?.let(::closeQuietly)
            closeQuietly(bluetooth)
            manager?.let(::closeQuietly)
        }
    }

    private enum class Stage { NETWORK, PHONE_JOINING, SESSION }

    private fun failureFor(reason: AapEndReason): AndroidAutoFailure? = when (reason) {
        AapEndReason.PHONE_DISCONNECTED, AapEndReason.LOCAL_CLOSE -> null
        AapEndReason.INCOMPATIBLE_VERSION -> AndroidAutoFailure.INCOMPATIBLE_PHONE
        AapEndReason.PROTOCOL_ERROR -> AndroidAutoFailure.PROTOCOL_ERROR
        AapEndReason.IO_ERROR -> AndroidAutoFailure.LINK_LOST
    }

    private fun fail(stage: Stage, error: Exception) {
        log("Connection failed during $stage: ${error.javaClass.simpleName} ${error.message.orEmpty()}")
        if (cancelled) return
        val failure = when (stage) {
            Stage.NETWORK -> AndroidAutoFailure.NETWORK_FAILED
            Stage.PHONE_JOINING -> AndroidAutoFailure.PHONE_DID_NOT_JOIN
            Stage.SESSION -> AndroidAutoFailure.LINK_LOST
        }
        AndroidAutoState.update(AndroidAutoPhase.ERROR, failure = failure, detail = error.message ?: error.javaClass.simpleName)
    }

    @Suppress("DEPRECATION")
    private fun displayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(context.resources.displayMetrics)
        }
        return metrics
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {
            // Releasing a resource that is already gone is not an error.
        } catch (error: RuntimeException) {
            Log.w(TAG, "Release failed: ${error.javaClass.simpleName}")
        }
    }

    companion object {
        const val TAG = "DiPlay-AndroidAuto"

        /** Long enough for a phone to switch Wi-Fi networks, short enough to give up on a stalled one. */
        private const val ACCEPT_TIMEOUT_MILLIS = 60_000
    }
}

/** Connects a running session to the head unit's speakers, microphone and projection screen. */
internal class AndroidAutoHost(
    private val context: Context,
    private val decoder: AapVideoDecoder,
    private val logger: (String) -> Unit,
) : AapSessionHost {
    override fun videoSink(): AapVideoSink = decoder

    override fun audioSink(channel: Int): AapAudioSink {
        val purpose = when (channel) {
            AapChannel.MEDIA_AUDIO -> AapAudioPlayer.Purpose.MEDIA
            AapChannel.SPEECH_AUDIO -> AapAudioPlayer.Purpose.GUIDANCE
            else -> AapAudioPlayer.Purpose.SYSTEM
        }
        return AapAudioPlayer(context, purpose, logger)
    }

    override fun microphone(): AapMicrophoneSource = AapMicrophone(AapAudioFormat(16_000, 16, 1), logger)

    override fun isNightMode(): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    override fun onPhoneIdentified(name: String) {
        AndroidAutoState.update(AndroidAutoPhase.PROJECTING, phoneName = name.ifBlank { null })
        showProjection()
    }

    override fun onProjectionRequested(projected: Boolean) {
        if (projected) {
            showProjection()
        } else {
            AndroidAutoRuntime.requestLeave()
        }
    }

    override fun onLog(message: String) = logger(message)

    private fun showProjection() {
        val intent = Intent(context, AndroidAutoActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            logger("The projection screen could not be opened")
        } catch (error: SecurityException) {
            logger("Android did not allow opening the projection screen from the background; use the notification")
        } catch (error: IllegalStateException) {
            logger("The projection screen could not be opened from the background; use the notification")
        }
    }
}
