package com.shilapi.xcertplay.androidauto

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Receives H.264 from the phone. All calls arrive on the session's reader thread. Arrays passed
 * in are only valid during the call, so a sink that queues work must copy them. [onStop] may be
 * called more than once.
 */
interface AapVideoSink {
    /** SPS and PPS in Annex B form. May repeat; the same bytes must not restart the decoder. */
    fun onCodecConfig(data: ByteArray)

    fun onFrame(timestampMicros: Long, data: ByteArray, offset: Int, length: Int)

    fun onStop()
}

/**
 * Plays one PCM stream. All calls arrive on the session's reader thread, and [write] must copy
 * what it keeps.
 */
interface AapAudioSink {
    fun start(format: AapAudioFormat)

    fun write(data: ByteArray, offset: Int, length: Int)

    fun stop()
}

/** Captures the car microphone for the phone's voice assistant and calls. */
interface AapMicrophoneSource : Closeable {
    /** Starts capturing. [onData] receives 16-bit mono PCM chunks until [close]. */
    fun open(onData: (ByteArray, Int) -> Unit)

    override fun close()
}

/** Everything the platform provides to a running session. */
interface AapSessionHost {
    fun videoSink(): AapVideoSink

    fun audioSink(channel: Int): AapAudioSink

    fun microphone(): AapMicrophoneSource

    fun isNightMode(): Boolean

    /** The phone's display name, known after service discovery. */
    fun onPhoneIdentified(name: String)

    /** The phone wants the head unit to show (true) or leave (false) the projected screen. */
    fun onProjectionRequested(projected: Boolean)

    fun onLog(message: String)
}

/** Why a session ended, for the status shown to the driver and the diagnostics. */
enum class AapEndReason { PHONE_DISCONNECTED, LOCAL_CLOSE, INCOMPATIBLE_VERSION, PROTOCOL_ERROR, IO_ERROR }

/**
 * One Android Auto connection, from the phone's TCP connection to its end.
 *
 * [run] blocks on the caller's thread and returns after the link ends. Everything else may be
 * called from any thread. The session never touches Android APIs itself; media and input go
 * through [AapSessionHost], so it can be exercised against a fake phone in plain JVM tests.
 */
class AapSession(
    input: InputStream,
    output: OutputStream,
    private val tls: AapTlsClient,
    private val config: AapHeadUnitConfig,
    private val host: AapSessionHost,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val reader = AapFrameReader(input)
    private val writer = AapFrameWriter(output)

    // Read by UI threads (touch, visibility) while the reader thread opens and closes channels.
    private val channelsOpen: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val mediaSessions = HashMap<Int, Int>()
    private val audioSinks = HashMap<Int, AapAudioSink>()
    private val audioActive = HashSet<Int>()
    private var videoSink: AapVideoSink? = null
    @Volatile private var microphone: AapMicrophoneSource? = null

    @Volatile private var closed = false
    @Volatile private var endReason: AapEndReason? = null
    @Volatile private var authenticated = false
    @Volatile private var projectionVisible = false
    @Volatile private var phoneWantsProjection = false
    @Volatile private var videoSetUp = false
    @Volatile private var sensorsActive = HashSet<Int>()
    @Volatile private var byeSent = false

    private var closeable: Closeable? = null

    // Callers on the UI thread must not touch the socket (StrictMode forbids it), so everything
    // sent from outside the reader thread is queued here and written in order.
    private val outbound: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aap-session-writer").apply { isDaemon = true }
    }

    /** Registers the transport so [close] can unblock a pending read. */
    fun attach(transport: Closeable) {
        closeable = transport
    }

    /** Runs the link until it ends and returns why. */
    fun run(): AapEndReason {
        try {
            send(AapChannel.CONTROL, encrypted = false, control = false, AapControl.VERSION_REQUEST,
                AapCodec.versionRequest(PROTOCOL_MAJOR, PROTOCOL_MINOR))
            while (!closed) {
                val message = reader.read()
                if (message == null) {
                    finish(AapEndReason.PHONE_DISCONNECTED)
                    break
                }
                handle(message)
            }
        } catch (error: SessionEnd) {
            finish(error.reason)
        } catch (error: ProtoException) {
            host.onLog("Android Auto protocol error: ${error.message}")
            finish(AapEndReason.PROTOCOL_ERROR)
        } catch (error: AapProtocolException) {
            host.onLog("Android Auto link error: ${error.message}")
            finish(AapEndReason.PROTOCOL_ERROR)
        } catch (error: IOException) {
            // A local close surfaces here as a socket exception; it is not a transport failure.
            finish(if (closed) AapEndReason.LOCAL_CLOSE else AapEndReason.IO_ERROR)
        } finally {
            outbound.shutdownNow()
            releaseMedia()
            try {
                closeable?.close()
            } catch (_: IOException) {
                // The link is already over.
            }
        }
        return endReason ?: AapEndReason.LOCAL_CLOSE
    }

    /** Ends the session from the car side. Unblocks [run]. */
    fun close() {
        if (closed) return
        finish(AapEndReason.LOCAL_CLOSE)
        try {
            closeable?.close()
        } catch (_: IOException) {
            // Closing is best effort; the reader fails either way.
        }
    }

    /** Asks the phone to end projection politely, then closes after [BYE_BYE_GRACE_MILLIS]. */
    fun disconnect() {
        if (closed || !authenticated) {
            close()
            return
        }
        if (byeSent) return
        byeSent = true
        safeSend(AapChannel.CONTROL, false, AapControl.BYE_BYE_REQUEST, AapCodec.byeBye(BYE_BYE_USER_SELECTION))
        Thread({
            try {
                Thread.sleep(BYE_BYE_GRACE_MILLIS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            close()
        }, "aap-bye-grace").apply { isDaemon = true }.start()
    }

    /** Called by the platform when the projection surface appears or goes away. */
    fun setProjectionVisible(visible: Boolean) {
        projectionVisible = visible
        // Before the video channel is set up there is nothing to grant; setup answers with the current state.
        if (!videoSetUp || closed) return
        // Grant focus as soon as the screen is ready, whether or not the phone asked for it first.
        val focus = if (visible) AapFocus.VIDEO_PROJECTED else AapFocus.VIDEO_NATIVE
        safeSend(AapChannel.VIDEO, false, AapMedia.VIDEO_FOCUS_NOTIFICATION,
            AapCodec.videoFocusNotification(focus, unsolicited = true))
    }

    fun sendTouch(action: Int, pointers: List<AapTouchPointer>, actionIndex: Int) {
        if (!channelsOpen.contains(AapChannel.INPUT) || closed || pointers.isEmpty()) return
        safeSend(AapChannel.INPUT, false, AapInput.INPUT_REPORT,
            AapCodec.touchReport(clockNanos(), pointers, actionIndex, action))
    }

    fun sendKey(keycode: Int, down: Boolean) {
        if (!channelsOpen.contains(AapChannel.INPUT) || closed) return
        safeSend(AapChannel.INPUT, false, AapInput.INPUT_REPORT,
            AapCodec.keyReport(clockNanos(), keycode, down, 0, false))
    }

    fun setNightMode(night: Boolean) {
        if (closed || AapSensorType.NIGHT_MODE !in sensorsActive) return
        safeSend(AapChannel.SENSOR, false, AapSensorMessage.BATCH, AapCodec.sensorBatchNightMode(night))
    }

    // ---- dispatch ----

    private fun handle(message: AapMessage) {
        when {
            message.channel == AapChannel.CONTROL -> handleControl(message)
            // The control flag marks the shared ids, but their values never clash with a channel's
            // own ids, so a sender that omits the flag is still understood.
            message.control || message.messageId == AapControl.CHANNEL_OPEN_REQUEST ||
                message.messageId == AapControl.CHANNEL_CLOSE -> handleChannelControl(message)
            else -> when (message.channel) {
                AapChannel.VIDEO -> handleVideo(message)
                AapChannel.MEDIA_AUDIO, AapChannel.SPEECH_AUDIO, AapChannel.SYSTEM_AUDIO -> handleAudio(message)
                AapChannel.MICROPHONE -> handleMicrophone(message)
                AapChannel.INPUT -> handleInput(message)
                AapChannel.SENSOR -> handleSensor(message)
                else -> host.onLog("Ignored message on unknown channel: $message")
            }
        }
    }

    private fun handleControl(message: AapMessage) {
        when (message.messageId) {
            AapControl.VERSION_RESPONSE -> {
                val version = AapCodec.parseVersionResponse(message.body())
                host.onLog("Phone speaks Android Auto protocol ${version.major}.${version.minor} status=${version.status}")
                if (version.status != AapStatus.SUCCESS) throw SessionEnd(AapEndReason.INCOMPATIBLE_VERSION)
                sendHandshake(tls.start())
            }
            AapControl.SSL_HANDSHAKE -> {
                val step = try {
                    tls.receive(message.body())
                } catch (error: IOException) {
                    host.onLog("TLS handshake failed: ${error.javaClass.simpleName}")
                    throw AapProtocolException("TLS handshake failed", error)
                }
                sendHandshake(step)
                if (step.complete && !authenticated) {
                    reader.cipher = tls
                    writer.cipher = tls
                    authenticated = true
                    send(AapChannel.CONTROL, false, false, AapControl.AUTH_COMPLETE, AapCodec.authComplete(AapStatus.SUCCESS))
                }
            }
            AapControl.SERVICE_DISCOVERY_REQUEST -> {
                requireAuthenticated(message)
                val request = AapCodec.parseServiceDiscoveryRequest(message.body())
                host.onPhoneIdentified(request.deviceName.ifBlank { request.label })
                send(AapChannel.CONTROL, true, false, AapControl.SERVICE_DISCOVERY_RESPONSE,
                    AapCodec.serviceDiscoveryResponse(config))
            }
            AapControl.PING_REQUEST -> {
                // Pings only make sense on the encrypted link; one before it exists is ignored.
                if (!authenticated) return
                send(AapChannel.CONTROL, true, false, AapControl.PING_RESPONSE,
                    AapCodec.ping(AapCodec.parsePingTimestamp(message.body())))
            }
            AapControl.PING_RESPONSE -> Unit
            AapControl.AUDIO_FOCUS_REQUEST -> {
                requireAuthenticated(message)
                val state = when (AapCodec.parseAudioFocusRequest(message.body())) {
                    AapFocus.AUDIO_REQUEST_GAIN -> AapFocus.AUDIO_STATE_GAIN
                    AapFocus.AUDIO_REQUEST_GAIN_TRANSIENT,
                    AapFocus.AUDIO_REQUEST_GAIN_TRANSIENT_MAY_DUCK -> AapFocus.AUDIO_STATE_GAIN_TRANSIENT
                    else -> AapFocus.AUDIO_STATE_LOSS
                }
                send(AapChannel.CONTROL, true, false, AapControl.AUDIO_FOCUS_NOTIFICATION,
                    AapCodec.audioFocusNotification(state, unsolicited = false))
            }
            AapControl.NAV_FOCUS_REQUEST -> {
                requireAuthenticated(message)
                send(AapChannel.CONTROL, true, false, AapControl.NAV_FOCUS_NOTIFICATION,
                    AapCodec.navFocusNotification(AapFocus.NAV_PROJECTED))
            }
            AapControl.BYE_BYE_REQUEST -> {
                requireAuthenticated(message)
                host.onLog("Phone asked to disconnect, reason=${AapCodec.parseByeByeReason(message.body())}")
                // Answer on this thread: the session closes right after, which would drop a queued reply.
                try {
                    send(AapChannel.CONTROL, true, false, AapControl.BYE_BYE_RESPONSE, ByteArray(0))
                } catch (_: IOException) {
                    // The phone is leaving anyway.
                }
                throw SessionEnd(AapEndReason.PHONE_DISCONNECTED)
            }
            AapControl.BYE_BYE_RESPONSE -> throw SessionEnd(AapEndReason.LOCAL_CLOSE)
            AapControl.VOICE_SESSION_NOTIFICATION, AapControl.BATTERY_STATUS_NOTIFICATION -> Unit
            else -> host.onLog("Unhandled control message: $message")
        }
    }

    private fun handleChannelControl(message: AapMessage) {
        requireAuthenticated(message)
        when (message.messageId) {
            AapControl.CHANNEL_OPEN_REQUEST -> {
                val request = AapCodec.parseChannelOpenRequest(message.body())
                val known = request.serviceId in DECLARED_CHANNELS && request.serviceId == message.channel
                if (known) channelsOpen += request.serviceId
                send(message.channel, true, true, AapControl.CHANNEL_OPEN_RESPONSE,
                    AapCodec.channelOpenResponse(if (known) AapStatus.SUCCESS else AapStatus.INVALID_SERVICE))
            }
            AapControl.CHANNEL_CLOSE -> channelsOpen -= message.channel
            else -> host.onLog("Unhandled channel control message: $message")
        }
    }

    // ---- video ----

    private fun handleVideo(message: AapMessage) {
        if (!isOpen(message)) return
        when (message.messageId) {
            AapMedia.SETUP -> {
                sendConfigReady(message.channel)
                videoSetUp = true
                if (projectionVisible) {
                    send(AapChannel.VIDEO, true, false, AapMedia.VIDEO_FOCUS_NOTIFICATION,
                        AapCodec.videoFocusNotification(AapFocus.VIDEO_PROJECTED, unsolicited = true))
                }
            }
            AapMedia.START -> {
                val start = AapCodec.parseMediaStart(message.body())
                mediaSessions[message.channel] = start.sessionId
                if (videoSink == null) videoSink = host.videoSink()
            }
            AapMedia.STOP -> {
                videoSink?.onStop()
                mediaSessions.remove(message.channel)
            }
            AapMedia.CODEC_CONFIG -> {
                videoSink?.onCodecConfig(message.body())
                ack(message.channel)
            }
            AapMedia.DATA -> {
                // After the 2-byte id and 8-byte timestamp comes the H.264 data.
                val dataStart = AapMessage.MESSAGE_ID_SIZE + 8
                val payload = message.payload
                val timestamp = AapCodec.mediaTimestamp(payload, AapMessage.MESSAGE_ID_SIZE)
                videoSink?.onFrame(timestamp, payload, dataStart, payload.size - dataStart)
                ack(message.channel)
            }
            AapMedia.VIDEO_FOCUS_REQUEST -> handleVideoFocusRequest(AapCodec.parseVideoFocusRequest(message.body()))
            else -> host.onLog("Unhandled video message: $message")
        }
    }

    private fun handleVideoFocusRequest(request: AapVideoFocusRequest) {
        if (request.mode == AapFocus.VIDEO_PROJECTED) {
            phoneWantsProjection = true
            host.onProjectionRequested(true)
            val focus = if (projectionVisible) AapFocus.VIDEO_PROJECTED else AapFocus.VIDEO_NATIVE
            send(AapChannel.VIDEO, true, false, AapMedia.VIDEO_FOCUS_NOTIFICATION,
                AapCodec.videoFocusNotification(focus, unsolicited = false))
        } else {
            phoneWantsProjection = false
            host.onProjectionRequested(false)
            send(AapChannel.VIDEO, true, false, AapMedia.VIDEO_FOCUS_NOTIFICATION,
                AapCodec.videoFocusNotification(AapFocus.VIDEO_NATIVE, unsolicited = false))
        }
    }

    // ---- audio ----

    private fun handleAudio(message: AapMessage) {
        if (!isOpen(message)) return
        val channel = message.channel
        when (message.messageId) {
            AapMedia.SETUP -> sendConfigReady(channel)
            AapMedia.START -> {
                val start = AapCodec.parseMediaStart(message.body())
                mediaSessions[channel] = start.sessionId
                val sink = audioSinks.getOrPut(channel) { host.audioSink(channel) }
                if (audioActive.add(channel)) sink.start(audioFormat(channel))
            }
            AapMedia.STOP -> {
                if (audioActive.remove(channel)) audioSinks[channel]?.stop()
                mediaSessions.remove(channel)
            }
            AapMedia.DATA -> {
                val timestampEnd = AapMessage.MESSAGE_ID_SIZE + 8
                if (message.payload.size < timestampEnd) throw ProtoException("Audio data without a timestamp")
                if (channel in audioActive) {
                    audioSinks[channel]?.write(message.payload, timestampEnd, message.payload.size - timestampEnd)
                }
                ack(channel)
            }
            else -> host.onLog("Unhandled audio message: $message")
        }
    }

    private fun audioFormat(channel: Int): AapAudioFormat = when (channel) {
        AapChannel.MEDIA_AUDIO -> config.mediaAudio
        AapChannel.SPEECH_AUDIO -> config.speechAudio
        else -> config.systemAudio
    }

    // ---- microphone ----

    private fun handleMicrophone(message: AapMessage) {
        if (!isOpen(message)) return
        when (message.messageId) {
            AapMedia.SETUP -> sendConfigReady(message.channel)
            AapMedia.MICROPHONE_REQUEST -> {
                val request = AapCodec.parseMicrophoneRequest(message.body())
                if (request.open) openMicrophone() else closeMicrophone()
                send(AapChannel.MICROPHONE, true, false, AapMedia.MICROPHONE_RESPONSE,
                    AapCodec.microphoneResponse(AapStatus.SUCCESS, MICROPHONE_SESSION))
            }
            AapMedia.ACK -> Unit
            else -> host.onLog("Unhandled microphone message: $message")
        }
    }

    private fun openMicrophone() {
        if (microphone != null) return
        val source = host.microphone()
        microphone = source
        source.open { data, length ->
            if (!closed && microphone === source) {
                safeSend(AapChannel.MICROPHONE, false, AapMedia.DATA,
                    AapCodec.mediaData(clockNanos() / 1000, data, 0, length))
            }
        }
    }

    private fun closeMicrophone() {
        val source = microphone ?: return
        microphone = null
        source.close()
    }

    // ---- input and sensors ----

    private fun handleInput(message: AapMessage) {
        if (!isOpen(message)) return
        when (message.messageId) {
            AapInput.KEY_BINDING_REQUEST -> {
                host.onLog("Phone bound ${AapCodec.parseKeyBindingRequest(message.body()).size} keys")
                send(AapChannel.INPUT, true, false, AapInput.KEY_BINDING_RESPONSE, AapCodec.statusOnly(AapStatus.SUCCESS))
            }
            else -> host.onLog("Unhandled input message: $message")
        }
    }

    private fun handleSensor(message: AapMessage) {
        if (!isOpen(message)) return
        if (message.messageId != AapSensorMessage.REQUEST) {
            host.onLog("Unhandled sensor message: $message")
            return
        }
        val request = AapCodec.parseSensorRequest(message.body())
        if (request.type !in config.sensors) {
            send(AapChannel.SENSOR, true, false, AapSensorMessage.RESPONSE, AapCodec.statusOnly(AapStatus.INVALID_SENSOR))
            return
        }
        send(AapChannel.SENSOR, true, false, AapSensorMessage.RESPONSE, AapCodec.statusOnly(AapStatus.SUCCESS))
        sensorsActive = HashSet(sensorsActive).apply { add(request.type) }
        // The phone expects a first reading right after it starts a sensor.
        when (request.type) {
            AapSensorType.DRIVING_STATUS -> send(AapChannel.SENSOR, true, false, AapSensorMessage.BATCH,
                AapCodec.sensorBatchDrivingStatus(DRIVING_STATUS_RESTRICTED))
            AapSensorType.NIGHT_MODE -> send(AapChannel.SENSOR, true, false, AapSensorMessage.BATCH,
                AapCodec.sensorBatchNightMode(host.isNightMode()))
        }
    }

    // ---- helpers ----

    private fun sendConfigReady(channel: Int) =
        send(channel, true, false, AapMedia.CONFIG, AapCodec.mediaConfigReady(MAX_UNACKED))

    private fun ack(channel: Int) {
        val session = mediaSessions[channel] ?: return
        send(channel, true, false, AapMedia.ACK, AapCodec.mediaAck(session, 1))
    }

    private fun sendHandshake(step: AapHandshakeStep) {
        if (step.toSend.isNotEmpty()) {
            send(AapChannel.CONTROL, false, false, AapControl.SSL_HANDSHAKE, step.toSend)
        }
    }

    private fun send(channel: Int, encrypted: Boolean, control: Boolean, id: Int, body: ByteArray) {
        writer.write(AapMessage.build(channel, encrypted, control, id, body))
    }

    /**
     * Queues a message from a thread other than the reader. A failure ends the session instead of
     * throwing, because nobody is waiting on the result.
     */
    private fun safeSend(channel: Int, control: Boolean, id: Int, body: ByteArray) {
        try {
            outbound.execute {
                if (closed) return@execute
                try {
                    send(channel, true, control, id, body)
                } catch (error: IOException) {
                    if (!closed) {
                        host.onLog("Android Auto write failed: ${error.javaClass.simpleName}")
                        close()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // The session already ended.
        }
    }

    private fun requireAuthenticated(message: AapMessage) {
        if (!authenticated) throw AapProtocolException("Message before authentication: $message")
    }

    /** True when [message] arrived on an open channel. Stray messages are logged and ignored, not fatal. */
    private fun isOpen(message: AapMessage): Boolean {
        requireAuthenticated(message)
        if (message.channel in channelsOpen) return true
        host.onLog("Ignored a message on a channel that is not open: $message")
        return false
    }

    private fun finish(reason: AapEndReason) {
        if (endReason == null) endReason = reason
        closed = true
    }

    private fun releaseMedia() {
        closeMicrophone()
        if (videoSink != null) videoSink?.onStop()
        for (channel in audioActive.toList()) audioSinks[channel]?.stop()
        audioActive.clear()
    }

    private class SessionEnd(val reason: AapEndReason) : RuntimeException(null, null, false, false)

    companion object {
        const val DEFAULT_PORT = 5288

        /** Protocol version announced in the version request. */
        const val PROTOCOL_MAJOR = 1
        const val PROTOCOL_MINOR = 1

        private const val MAX_UNACKED = 1
        private const val MICROPHONE_SESSION = 1
        /**
         * The head unit does not know whether the car is moving, so it always asks the phone to apply
         * its driving restrictions: no keyboard input and limited message length (protocol flags 2 and 16).
         */
        internal const val DRIVING_STATUS_RESTRICTED = AapDrivingStatus.NO_KEYBOARD_INPUT or AapDrivingStatus.LIMIT_MESSAGE_LENGTH
        private const val BYE_BYE_USER_SELECTION = 1
        private const val BYE_BYE_GRACE_MILLIS = 1_500L

        private val DECLARED_CHANNELS = setOf(
            AapChannel.INPUT, AapChannel.SENSOR, AapChannel.VIDEO, AapChannel.MEDIA_AUDIO,
            AapChannel.SPEECH_AUDIO, AapChannel.SYSTEM_AUDIO, AapChannel.MICROPHONE,
        )
    }
}
