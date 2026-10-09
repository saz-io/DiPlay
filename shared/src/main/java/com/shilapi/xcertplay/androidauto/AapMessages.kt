package com.shilapi.xcertplay.androidauto

/**
 * Message ids, enum values and codecs for the subset of the Android Auto protocol that a head
 * unit needs to project a phone. Field numbers follow the publicly documented protocol
 * definitions (see docs/ANDROID_AUTO.md for the reference and licence notes).
 */

/** Service (channel) ids this head unit declares during service discovery. Control is always 0. */
object AapChannel {
    const val CONTROL = 0
    const val INPUT = 1
    const val SENSOR = 2
    const val VIDEO = 3
    const val MEDIA_AUDIO = 4
    const val SPEECH_AUDIO = 5
    const val SYSTEM_AUDIO = 6
    const val MICROPHONE = 7
}

/** Message ids on the control channel, and the shared control ids on every channel. */
object AapControl {
    const val VERSION_REQUEST = 1
    const val VERSION_RESPONSE = 2
    const val SSL_HANDSHAKE = 3
    const val AUTH_COMPLETE = 4
    const val SERVICE_DISCOVERY_REQUEST = 5
    const val SERVICE_DISCOVERY_RESPONSE = 6
    const val CHANNEL_OPEN_REQUEST = 7
    const val CHANNEL_OPEN_RESPONSE = 8
    const val CHANNEL_CLOSE = 9
    const val PING_REQUEST = 11
    const val PING_RESPONSE = 12
    const val NAV_FOCUS_REQUEST = 13
    const val NAV_FOCUS_NOTIFICATION = 14
    const val BYE_BYE_REQUEST = 15
    const val BYE_BYE_RESPONSE = 16
    const val VOICE_SESSION_NOTIFICATION = 17
    const val AUDIO_FOCUS_REQUEST = 18
    const val AUDIO_FOCUS_NOTIFICATION = 19
    const val BATTERY_STATUS_NOTIFICATION = 23
}

/** Message ids on the audio, video and microphone channels. */
object AapMedia {
    const val DATA = 0x0000
    const val CODEC_CONFIG = 0x0001
    const val SETUP = 0x8000
    const val START = 0x8001
    const val STOP = 0x8002
    const val CONFIG = 0x8003
    const val ACK = 0x8004
    const val MICROPHONE_REQUEST = 0x8005
    const val MICROPHONE_RESPONSE = 0x8006
    const val VIDEO_FOCUS_REQUEST = 0x8007
    const val VIDEO_FOCUS_NOTIFICATION = 0x8008
}

object AapInput {
    const val INPUT_REPORT = 0x8001
    const val KEY_BINDING_REQUEST = 0x8002
    const val KEY_BINDING_RESPONSE = 0x8003
}

object AapSensorMessage {
    const val REQUEST = 0x8001
    const val RESPONSE = 0x8002
    const val BATCH = 0x8003
}

object AapStatus {
    const val SUCCESS = 0
    const val INVALID_SERVICE = -4
    const val INVALID_SENSOR = -9
    const val KEYCODE_NOT_BOUND = -18
    const val COMMAND_NOT_SUPPORTED = -250
}

object AapDrivingStatus {
    const val UNRESTRICTED = 0
    const val NO_VIDEO = 1
    const val NO_KEYBOARD_INPUT = 2
    const val NO_VOICE_INPUT = 4
    const val NO_CONFIG = 8
    const val LIMIT_MESSAGE_LENGTH = 16
}

object AapSensorType {
    const val LOCATION = 1
    const val NIGHT_MODE = 10
    const val DRIVING_STATUS = 13
}

object AapFocus {
    const val AUDIO_REQUEST_GAIN = 1
    const val AUDIO_REQUEST_GAIN_TRANSIENT = 2
    const val AUDIO_REQUEST_GAIN_TRANSIENT_MAY_DUCK = 3
    const val AUDIO_REQUEST_RELEASE = 4
    const val AUDIO_STATE_GAIN = 1
    const val AUDIO_STATE_GAIN_TRANSIENT = 2
    const val AUDIO_STATE_LOSS = 3
    const val NAV_NATIVE = 1
    const val NAV_PROJECTED = 2
    const val VIDEO_PROJECTED = 1
    const val VIDEO_NATIVE = 2
}

object AapPointerAction {
    const val DOWN = 0
    const val UP = 1
    const val MOVED = 2
    const val POINTER_DOWN = 5
    const val POINTER_UP = 6
}

enum class AapVideoResolution(val code: Int, val width: Int, val height: Int) {
    R800X480(1, 800, 480),
    R1280X720(2, 1280, 720),
    R1920X1080(3, 1920, 1080),
}

class AapVideoConfig(
    val resolution: AapVideoResolution,
    val framesPerSecond: Int,
    /** Pixels the phone leaves unused horizontally, split evenly left and right. */
    val widthMargin: Int,
    /** Pixels the phone leaves unused vertically, split evenly top and bottom. */
    val heightMargin: Int,
    val density: Int,
) {
    init {
        require(framesPerSecond == 30 || framesPerSecond == 60) { "Android Auto supports 30 or 60 fps" }
        require(widthMargin in 0 until resolution.width && heightMargin in 0 until resolution.height) {
            "Margins must leave a visible area"
        }
        require(density in 80..640) { "Density must be a plausible dpi" }
    }

    /** Width of the picture the driver sees after the margins are cropped. */
    val visibleWidth: Int get() = resolution.width - widthMargin
    val visibleHeight: Int get() = resolution.height - heightMargin
}

class AapAudioFormat(val sampleRate: Int, val bitsPerSample: Int, val channels: Int)

/** The head-unit identity strings shown to, and logged by, the phone. */
class AapHeadUnitInfo(
    val make: String = "DiPlay",
    val model: String = "Android head unit",
    val year: String = "2026",
    val vehicleId: String = "diplay",
    val headUnitMake: String = "DiPlay",
    val headUnitModel: String = "Android head unit",
    val softwareBuild: String = "1",
    val softwareVersion: String = "1",
    val displayName: String = "DiPlay",
    /** 0 = left-hand drive, 1 = right-hand drive. */
    val driverPosition: Int = 0,
)

/** Everything service discovery needs to describe this head unit. */
class AapHeadUnitConfig(
    val info: AapHeadUnitInfo,
    val video: AapVideoConfig,
    val mediaAudio: AapAudioFormat = AapAudioFormat(48_000, 16, 2),
    val speechAudio: AapAudioFormat = AapAudioFormat(16_000, 16, 1),
    val systemAudio: AapAudioFormat = AapAudioFormat(16_000, 16, 1),
    val microphone: AapAudioFormat = AapAudioFormat(16_000, 16, 1),
    val keycodes: List<Int> = DEFAULT_KEYCODES,
    val sensors: List<Int> = listOf(AapSensorType.DRIVING_STATUS, AapSensorType.NIGHT_MODE),
) {
    companion object {
        /**
         * Android key codes; the protocol uses the same numbering. Back and Home are not listed:
         * they stay with the system so the driver can always leave the projection screen.
         */
        val DEFAULT_KEYCODES = listOf(
            5, 6, 19, 20, 21, 22, 23, 84, 85, 86, 87, 88, 126, 127,
        )
    }
}

class AapVersionResponse(val major: Int, val minor: Int, val status: Int)

class AapChannelOpenRequest(val priority: Int, val serviceId: Int)

class AapServiceDiscoveryRequest(val deviceName: String, val label: String)

class AapMediaStart(val sessionId: Int, val configurationIndex: Int)

class AapVideoFocusRequest(val mode: Int, val reason: Int)

class AapMicrophoneRequest(val open: Boolean, val maxUnacked: Int)

class AapMediaAck(val sessionId: Int, val ack: Int)

class AapSensorRequest(val type: Int, val minUpdatePeriod: Long)

class AapTouchPointer(val id: Int, val x: Int, val y: Int)

/** Builds and parses message bodies. Every builder returns only the body, without the message id. */
object AapCodec {
    // ---- control ----

    fun versionRequest(major: Int, minor: Int): ByteArray =
        byteArrayOf((major ushr 8).toByte(), major.toByte(), (minor ushr 8).toByte(), minor.toByte())

    /** The phone answers with `major(2) minor(2) status(2)`; a status of 0 means compatible. */
    fun parseVersionResponse(body: ByteArray): AapVersionResponse {
        if (body.size < 6) throw ProtoException("Version response is too short")
        fun u16(at: Int) = ((body[at].toInt() and 0xff) shl 8) or (body[at + 1].toInt() and 0xff)
        return AapVersionResponse(u16(0), u16(2), u16(4).toShort().toInt())
    }

    fun authComplete(status: Int): ByteArray = ProtoWriter().int32(1, status).toByteArray()

    fun channelOpenResponse(status: Int): ByteArray = ProtoWriter().enum(1, status).toByteArray()

    fun parseChannelOpenRequest(body: ByteArray): AapChannelOpenRequest {
        var priority = 0
        var serviceId = -1
        val reader = ProtoReader(body)
        while (reader.next()) {
            when (reader.field) {
                1 -> priority = reader.sint32
                2 -> serviceId = reader.int
            }
        }
        if (serviceId < 0) throw ProtoException("Channel open request without a service id")
        return AapChannelOpenRequest(priority, serviceId)
    }

    fun parseServiceDiscoveryRequest(body: ByteArray): AapServiceDiscoveryRequest {
        var name = ""
        var label = ""
        val reader = ProtoReader(body)
        while (reader.next()) {
            if (!reader.isLengthDelimited) continue
            when (reader.field) {
                4 -> label = reader.string()
                5 -> name = reader.string()
            }
        }
        return AapServiceDiscoveryRequest(name, label)
    }

    fun ping(timestamp: Long): ByteArray = ProtoWriter().int64(1, timestamp).toByteArray()

    fun parsePingTimestamp(body: ByteArray): Long {
        val reader = ProtoReader(body)
        while (reader.next()) if (reader.field == 1) return reader.long
        return 0L
    }

    fun audioFocusNotification(state: Int, unsolicited: Boolean): ByteArray =
        ProtoWriter().enum(1, state).bool(2, unsolicited).toByteArray()

    fun parseAudioFocusRequest(body: ByteArray): Int = parseFirstVarint(body, 1)

    fun navFocusNotification(type: Int): ByteArray = ProtoWriter().enum(1, type).toByteArray()

    fun byeBye(reason: Int): ByteArray = ProtoWriter().enum(1, reason).toByteArray()

    fun parseByeByeReason(body: ByteArray): Int = parseFirstVarint(body, 1)

    // ---- audio, video and microphone ----

    fun parseSetupType(body: ByteArray): Int = parseFirstVarint(body, 1)

    fun mediaConfigReady(maxUnacked: Int): ByteArray =
        ProtoWriter().enum(1, CONFIG_STATUS_READY).uint32(2, maxUnacked).uint32(3, 0).toByteArray()

    fun parseMediaStart(body: ByteArray): AapMediaStart {
        var session = 0
        var index = 0
        val reader = ProtoReader(body)
        while (reader.next()) {
            when (reader.field) {
                1 -> session = reader.int
                2 -> index = reader.int
            }
        }
        return AapMediaStart(session, index)
    }

    fun mediaAck(sessionId: Int, ack: Int): ByteArray =
        ProtoWriter().int32(1, sessionId).uint32(2, ack).toByteArray()

    fun parseMediaAck(body: ByteArray): AapMediaAck {
        var session = 0
        var ack = 0
        val reader = ProtoReader(body)
        while (reader.next()) {
            when (reader.field) {
                1 -> session = reader.int
                2 -> ack = reader.int
            }
        }
        return AapMediaAck(session, ack)
    }

    fun videoFocusNotification(focus: Int, unsolicited: Boolean): ByteArray =
        ProtoWriter().enum(1, focus).bool(2, unsolicited).toByteArray()

    fun parseVideoFocusRequest(body: ByteArray): AapVideoFocusRequest {
        var mode = AapFocus.VIDEO_PROJECTED
        var reason = 0
        val reader = ProtoReader(body)
        while (reader.next()) {
            when (reader.field) {
                2 -> mode = reader.int
                3 -> reason = reader.int
            }
        }
        return AapVideoFocusRequest(mode, reason)
    }

    fun parseMicrophoneRequest(body: ByteArray): AapMicrophoneRequest {
        var open = false
        var maxUnacked = 0
        val reader = ProtoReader(body)
        while (reader.next()) {
            when (reader.field) {
                1 -> open = reader.bool
                4 -> maxUnacked = reader.int
            }
        }
        return AapMicrophoneRequest(open, maxUnacked)
    }

    fun microphoneResponse(status: Int, sessionId: Int): ByteArray =
        ProtoWriter().int32(1, status).int32(2, sessionId).toByteArray()

    /** A media data body: 8-byte big-endian timestamp in microseconds, then the payload. */
    fun mediaData(timestampMicros: Long, data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        val out = ByteArray(8 + length)
        for (index in 0 until 8) out[index] = (timestampMicros ushr (56 - 8 * index)).toByte()
        System.arraycopy(data, offset, out, 8, length)
        return out
    }

    /** Reads the big-endian timestamp that starts at [offset]. */
    fun mediaTimestamp(data: ByteArray, offset: Int = 0): Long {
        if (offset < 0 || data.size - offset < 8) throw ProtoException("Media data without a timestamp")
        var value = 0L
        for (index in 0 until 8) value = (value shl 8) or (data[offset + index].toLong() and 0xff)
        return value
    }

    // ---- sensors ----

    fun parseSensorRequest(body: ByteArray): AapSensorRequest {
        var type = 0
        var period = 0L
        val reader = ProtoReader(body)
        while (reader.next()) {
            when (reader.field) {
                1 -> type = reader.int
                2 -> period = reader.long
            }
        }
        return AapSensorRequest(type, period)
    }

    fun statusOnly(status: Int): ByteArray = ProtoWriter().enum(1, status).toByteArray()

    fun sensorBatchDrivingStatus(status: Int): ByteArray =
        ProtoWriter().message(13) { int32(1, status) }.toByteArray()

    fun sensorBatchNightMode(night: Boolean): ByteArray =
        ProtoWriter().message(10) { bool(1, night) }.toByteArray()

    // ---- input ----

    fun touchReport(timestampNanos: Long, pointers: List<AapTouchPointer>, actionIndex: Int, action: Int): ByteArray =
        ProtoWriter().int64(1, timestampNanos).message(3) {
            for (pointer in pointers) {
                message(1) {
                    uint32(1, pointer.x)
                    uint32(2, pointer.y)
                    uint32(3, pointer.id)
                }
            }
            uint32(2, actionIndex)
            enum(3, action)
        }.toByteArray()

    fun keyReport(timestampNanos: Long, keycode: Int, down: Boolean, metaState: Int, longPress: Boolean): ByteArray =
        ProtoWriter().int64(1, timestampNanos).message(4) {
            message(1) {
                uint32(1, keycode)
                bool(2, down)
                uint32(3, metaState)
                bool(4, longPress)
            }
        }.toByteArray()

    fun parseKeyBindingRequest(body: ByteArray): List<Int> {
        val keys = ArrayList<Int>()
        val reader = ProtoReader(body)
        while (reader.next()) if (reader.field == 1) reader.addInt32s(keys)
        return keys
    }

    // ---- service discovery ----

    fun serviceDiscoveryResponse(config: AapHeadUnitConfig): ByteArray {
        val info = config.info
        val writer = ProtoWriter()
        writer.message(1) {
            int32(1, AapChannel.SENSOR)
            message(2) {
                for (sensor in config.sensors) message(1) { enum(1, sensor) }
            }
        }
        writer.message(1) {
            int32(1, AapChannel.VIDEO)
            message(3) {
                enum(1, MEDIA_CODEC_H264)
                val video = config.video
                message(4) {
                    enum(1, video.resolution.code)
                    enum(2, if (video.framesPerSecond == 60) FPS_60 else FPS_30)
                    uint32(3, video.widthMargin)
                    uint32(4, video.heightMargin)
                    uint32(5, video.density)
                    enum(10, MEDIA_CODEC_H264)
                }
                bool(5, true)
                uint32(6, 0)
                enum(7, DISPLAY_TYPE_MAIN)
            }
        }
        writer.audioSink(AapChannel.MEDIA_AUDIO, AUDIO_STREAM_MEDIA, config.mediaAudio, availableInCall = false)
        writer.audioSink(AapChannel.SPEECH_AUDIO, AUDIO_STREAM_GUIDANCE, config.speechAudio, availableInCall = true)
        writer.audioSink(AapChannel.SYSTEM_AUDIO, AUDIO_STREAM_SYSTEM, config.systemAudio, availableInCall = true)
        writer.message(1) {
            int32(1, AapChannel.MICROPHONE)
            message(5) {
                enum(1, MEDIA_CODEC_PCM)
                message(2) { audioConfiguration(config.microphone) }
                bool(3, true)
            }
        }
        writer.message(1) {
            int32(1, AapChannel.INPUT)
            message(4) {
                packedInt32(1, config.keycodes)
                message(2) {
                    int32(1, config.video.resolution.width)
                    int32(2, config.video.resolution.height)
                    enum(3, TOUCHSCREEN_CAPACITIVE)
                }
            }
        }
        // Deprecated flat fields: older phone releases still read them.
        writer.string(2, info.make).string(3, info.model).string(4, info.year).string(5, info.vehicleId)
        writer.enum(6, info.driverPosition)
        writer.string(7, info.headUnitMake).string(8, info.headUnitModel)
        writer.string(9, info.softwareBuild).string(10, info.softwareVersion)
        writer.string(14, info.displayName)
        writer.message(17) {
            string(1, info.make)
            string(2, info.model)
            string(3, info.year)
            string(4, info.vehicleId)
            string(5, info.headUnitMake)
            string(6, info.headUnitModel)
            string(7, info.softwareBuild)
            string(8, info.softwareVersion)
        }
        return writer.toByteArray()
    }

    private fun ProtoWriter.audioSink(service: Int, streamType: Int, format: AapAudioFormat, availableInCall: Boolean) {
        message(1) {
            int32(1, service)
            message(3) {
                enum(1, MEDIA_CODEC_PCM)
                enum(2, streamType)
                message(3) { audioConfiguration(format) }
                bool(5, availableInCall)
            }
        }
    }

    private fun ProtoWriter.audioConfiguration(format: AapAudioFormat) {
        uint32(1, format.sampleRate)
        uint32(2, format.bitsPerSample)
        uint32(3, format.channels)
    }

    private fun parseFirstVarint(body: ByteArray, field: Int): Int {
        val reader = ProtoReader(body)
        while (reader.next()) if (reader.field == field && !reader.isLengthDelimited) return reader.int
        return 0
    }

    private const val CONFIG_STATUS_READY = 2
    private const val MEDIA_CODEC_PCM = 1
    private const val MEDIA_CODEC_H264 = 3
    private const val AUDIO_STREAM_GUIDANCE = 1
    private const val AUDIO_STREAM_SYSTEM = 2
    private const val AUDIO_STREAM_MEDIA = 3
    private const val FPS_60 = 1
    private const val FPS_30 = 2
    private const val DISPLAY_TYPE_MAIN = 0
    private const val TOUCHSCREEN_CAPACITIVE = 1
}
