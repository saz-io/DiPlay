package com.shilapi.xcertplay.androidauto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AapMessagesTest {
    private val video = AapVideoConfig(AapVideoResolution.R1920X1080, 30, widthMargin = 0, heightMargin = 360, density = 160)
    private val config = AapHeadUnitConfig(AapHeadUnitInfo(driverPosition = 1), video)

    /** Field number -> every nested message with that number, for quick structural assertions. */
    private fun fields(bytes: ByteArray, number: Int): List<ByteArray> {
        val found = ArrayList<ByteArray>()
        val reader = ProtoReader(bytes)
        while (reader.next()) if (reader.field == number && reader.isLengthDelimited) found += reader.bytes()
        return found
    }

    private fun varint(bytes: ByteArray, number: Int): Long? {
        val reader = ProtoReader(bytes)
        while (reader.next()) if (reader.field == number && !reader.isLengthDelimited) return reader.long
        return null
    }

    private fun service(response: ByteArray, id: Int): ByteArray =
        fields(response, 1).single { varint(it, 1) == id.toLong() }

    @Test fun versionRequestIsFourBigEndianBytes() {
        assertArrayEquals(byteArrayOf(0, 1, 0, 7), AapCodec.versionRequest(1, 7))
    }

    @Test fun versionResponseStatusIsSigned() {
        val ok = AapCodec.parseVersionResponse(byteArrayOf(0, 1, 0, 7, 0, 0))
        assertEquals(1, ok.major); assertEquals(7, ok.minor); assertEquals(0, ok.status)
        assertEquals(-1, AapCodec.parseVersionResponse(byteArrayOf(0, 1, 0, 0, -1, -1)).status)
        try {
            AapCodec.parseVersionResponse(byteArrayOf(0, 1))
            fail()
        } catch (_: ProtoException) {
        }
    }

    @Test fun serviceDiscoveryDeclaresEveryChannelWithItsOwnId() {
        val response = AapCodec.serviceDiscoveryResponse(config)
        val ids = fields(response, 1).map { varint(it, 1)!!.toInt() }
        assertEquals(
            setOf(AapChannel.SENSOR, AapChannel.VIDEO, AapChannel.MEDIA_AUDIO, AapChannel.SPEECH_AUDIO,
                AapChannel.SYSTEM_AUDIO, AapChannel.MICROPHONE, AapChannel.INPUT),
            ids.toSet(),
        )
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun videoServiceCarriesResolutionFrameRateMarginsAndDensity() {
        val sink = fields(service(AapCodec.serviceDiscoveryResponse(config), AapChannel.VIDEO), 3).single()
        assertEquals(3L, varint(sink, 1)) // H.264 baseline
        val videoConfig = fields(sink, 4).single()
        assertEquals(3L, varint(videoConfig, 1)) // 1920x1080
        assertEquals(2L, varint(videoConfig, 2)) // 30 fps
        assertEquals(0L, varint(videoConfig, 3))
        assertEquals(360L, varint(videoConfig, 4))
        assertEquals(160L, varint(videoConfig, 5))
        assertEquals(3L, varint(videoConfig, 10))
    }

    @Test fun sixtyFramesPerSecondUsesTheOtherEnumValue() {
        val sixty = AapHeadUnitConfig(AapHeadUnitInfo(), AapVideoConfig(AapVideoResolution.R1280X720, 60, 0, 0, 140))
        val sink = fields(service(AapCodec.serviceDiscoveryResponse(sixty), AapChannel.VIDEO), 3).single()
        assertEquals(1L, varint(fields(sink, 4).single(), 2))
    }

    @Test fun touchScreenMatchesTheVideoResolution() {
        val input = fields(service(AapCodec.serviceDiscoveryResponse(config), AapChannel.INPUT), 4).single()
        val screen = fields(input, 2).single()
        assertEquals(1920L, varint(screen, 1))
        assertEquals(1080L, varint(screen, 2))
        val keys = ArrayList<Int>()
        ProtoReader(input).apply { while (next()) if (field == 1) addInt32s(keys) }
        assertEquals(AapHeadUnitConfig.DEFAULT_KEYCODES, keys)
    }

    @Test fun audioServicesDescribeTheirPcmFormats() {
        val response = AapCodec.serviceDiscoveryResponse(config)
        fun format(channel: Int): Triple<Long?, Long?, Long?> {
            val sink = fields(service(response, channel), 3).single()
            val audio = fields(sink, 3).single()
            return Triple(varint(audio, 1), varint(audio, 2), varint(audio, 3))
        }
        assertEquals(Triple(48_000L, 16L, 2L), format(AapChannel.MEDIA_AUDIO))
        assertEquals(Triple(16_000L, 16L, 1L), format(AapChannel.SPEECH_AUDIO))
        assertEquals(Triple(16_000L, 16L, 1L), format(AapChannel.SYSTEM_AUDIO))
        val mic = fields(service(response, AapChannel.MICROPHONE), 5).single()
        assertEquals(1L, varint(mic, 1))
        assertEquals(16_000L, varint(fields(mic, 2).single(), 1))
        val streamTypes = listOf(AapChannel.MEDIA_AUDIO, AapChannel.SPEECH_AUDIO, AapChannel.SYSTEM_AUDIO).map {
            varint(fields(service(response, it), 3).single(), 2)
        }
        assertEquals(listOf(3L, 1L, 2L), streamTypes) // media, guidance, system
    }

    @Test fun sensorServiceListsOnlyWhatTheHeadUnitCanProvide() {
        val sensors = fields(fields(service(AapCodec.serviceDiscoveryResponse(config), AapChannel.SENSOR), 2).single(), 1)
        assertEquals(listOf(13L, 10L), sensors.map { varint(it, 1) })
    }

    @Test fun headUnitIdentityIsSentInTheCurrentAndTheLegacyFields() {
        val response = AapCodec.serviceDiscoveryResponse(config)
        val reader = ProtoReader(response)
        val seen = HashMap<Int, String>()
        var driver = -1L
        while (reader.next()) {
            if (reader.field == 6) driver = reader.long
            if (reader.isLengthDelimited && reader.field in listOf(2, 3, 14)) seen[reader.field] = reader.string()
        }
        assertEquals(1L, driver)
        assertEquals("DiPlay", seen[2])
        assertEquals("DiPlay", seen[14])
        assertEquals("DiPlay", ProtoReader(fields(response, 17).single()).let { it.next(); it.string() })
    }

    @Test fun videoConfigRejectsImplausibleValues() {
        for (build in listOf<() -> Any>(
            { AapVideoConfig(AapVideoResolution.R800X480, 24, 0, 0, 160) },
            { AapVideoConfig(AapVideoResolution.R800X480, 30, 800, 0, 160) },
            { AapVideoConfig(AapVideoResolution.R800X480, 30, 0, 0, 10) },
        )) {
            try {
                build()
                fail()
            } catch (_: IllegalArgumentException) {
            }
        }
        val c = AapVideoConfig(AapVideoResolution.R1920X1080, 30, 0, 360, 160)
        assertEquals(1920, c.visibleWidth); assertEquals(720, c.visibleHeight)
    }

    @Test fun channelOpenRequestUsesZigZagPriority() {
        val body = ProtoWriter().sint32(1, -3).int32(2, AapChannel.VIDEO).toByteArray()
        val request = AapCodec.parseChannelOpenRequest(body)
        assertEquals(-3, request.priority)
        assertEquals(AapChannel.VIDEO, request.serviceId)
        try {
            AapCodec.parseChannelOpenRequest(ProtoWriter().sint32(1, 0).toByteArray())
            fail()
        } catch (_: ProtoException) {
        }
    }

    @Test fun statusCodesKeepTheirSign() {
        val reader = ProtoReader(AapCodec.channelOpenResponse(AapStatus.INVALID_SERVICE))
        assertTrue(reader.next())
        assertEquals(-4, reader.int)
    }

    @Test fun mediaDataRoundTripsThroughTheTimestampPrefix() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val body = AapCodec.mediaData(0x0102030405060708L, data, 1, 3)
        assertEquals(11, body.size)
        assertEquals(0x0102030405060708L, AapCodec.mediaTimestamp(body))
        assertArrayEquals(byteArrayOf(2, 3, 4), body.copyOfRange(8, 11))
        try {
            AapCodec.mediaTimestamp(ByteArray(7))
            fail()
        } catch (_: ProtoException) {
        }
    }

    @Test fun touchReportEncodesPointersAndAction() {
        val body = AapCodec.touchReport(
            123L, listOf(AapTouchPointer(0, 100, 200), AapTouchPointer(1, 300, 400)), 1, AapPointerAction.POINTER_DOWN,
        )
        val reader = ProtoReader(body)
        assertTrue(reader.next()); assertEquals(123L, reader.long)
        assertTrue(reader.next()); assertEquals(3, reader.field)
        val touch = reader.message()
        val pointers = ArrayList<Triple<Int, Int, Int>>()
        var action = -1; var index = -1
        while (touch.next()) when (touch.field) {
            1 -> touch.message().let { p ->
                val v = HashMap<Int, Int>()
                while (p.next()) v[p.field] = p.int
                pointers += Triple(v[1]!!, v[2]!!, v[3]!!)
            }
            2 -> index = touch.int
            3 -> action = touch.int
        }
        assertEquals(listOf(Triple(100, 200, 0), Triple(300, 400, 1)), pointers)
        assertEquals(1, index)
        assertEquals(5, action)
    }

    @Test fun keyReportEncodesDownAndMetaState() {
        val body = AapCodec.keyReport(9L, 85, down = true, metaState = 0, longPress = false)
        val reader = ProtoReader(body)
        assertTrue(reader.next())
        assertTrue(reader.next())
        assertEquals(4, reader.field)
        val key = reader.message().let { it.next(); it.message() }
        val values = HashMap<Int, Long>()
        while (key.next()) values[key.field] = key.long
        assertEquals(85L, values[1])
        assertEquals(1L, values[2])
        assertFalse(values[4] == 1L)
    }

    @Test fun mediaConfigIsReadyWithOneConfiguration() {
        val reader = ProtoReader(AapCodec.mediaConfigReady(1))
        val values = HashMap<Int, Long>()
        while (reader.next()) values[reader.field] = reader.long
        assertEquals(2L, values[1]) // STATUS_READY
        assertEquals(1L, values[2])
        assertEquals(0L, values[3])
    }

    @Test fun parsesPhoneRequests() {
        val setupType = AapCodec.parseSetupType(ProtoWriter().enum(1, 3).toByteArray())
        assertEquals(3, setupType)
        val start = AapCodec.parseMediaStart(ProtoWriter().int32(1, 42).uint32(2, 1).toByteArray())
        assertEquals(42, start.sessionId); assertEquals(1, start.configurationIndex)
        val focus = AapCodec.parseVideoFocusRequest(ProtoWriter().enum(2, 2).enum(3, 1).toByteArray())
        assertEquals(2, focus.mode); assertEquals(1, focus.reason)
        val mic = AapCodec.parseMicrophoneRequest(ProtoWriter().bool(1, true).int32(4, 5).toByteArray())
        assertTrue(mic.open); assertEquals(5, mic.maxUnacked)
        val sensor = AapCodec.parseSensorRequest(ProtoWriter().enum(1, 13).int64(2, 1_000_000L).toByteArray())
        assertEquals(13, sensor.type); assertEquals(1_000_000L, sensor.minUpdatePeriod)
        val name = AapCodec.parseServiceDiscoveryRequest(ProtoWriter().string(4, "Label").string(5, "Pixel 9").toByteArray())
        assertEquals("Pixel 9", name.deviceName); assertEquals("Label", name.label)
        assertEquals(555L, AapCodec.parsePingTimestamp(AapCodec.ping(555L)))
        assertEquals(listOf(4, 85), AapCodec.parseKeyBindingRequest(ProtoWriter().packedInt32(1, listOf(4, 85)).toByteArray()))
    }
}
