package com.shilapi.xcertplay.androidauto

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AapSessionTest {
    private val headIdentity = TestIdentities.headUnit
    private val phoneIdentity = TestIdentities.phone
    private val config = AapHeadUnitConfig(
        AapHeadUnitInfo(),
        AapVideoConfig(AapVideoResolution.R1280X720, 30, widthMargin = 0, heightMargin = 0, density = 140),
    )

    private lateinit var link: LoopbackLink
    private lateinit var host: RecordingHost
    private lateinit var session: AapSession
    private lateinit var phone: FakePhone
    private val ended = LinkedBlockingQueue<AapEndReason>()

    @Before fun setUp() {
        link = LoopbackLink()
        host = RecordingHost(night = true)
        session = AapSession(
            link.head.getInputStream(), link.head.getOutputStream(),
            AapTlsClient(AapHeadUnitIdentity.parsePem(headIdentity.pem())), config, host,
        )
        session.attach(link.head)
        phone = FakePhone(link.phone, PhoneTls(phoneIdentity))
        Thread({ ended.add(session.run()) }, "test-aap-session").apply { isDaemon = true }.start()
    }

    @After fun tearDown() {
        session.close()
        link.close()
    }

    private fun awaitEnd(): AapEndReason = ended.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("the session did not end")

    private fun varints(body: ByteArray): Map<Int, Long> {
        val result = HashMap<Int, Long>()
        val reader = ProtoReader(body)
        while (reader.next()) if (!reader.isLengthDelimited) result[reader.field] = reader.long
        return result
    }

    private fun openAllChannels() {
        listOf(AapChannel.SENSOR, AapChannel.VIDEO, AapChannel.MEDIA_AUDIO, AapChannel.SPEECH_AUDIO,
            AapChannel.SYSTEM_AUDIO, AapChannel.MICROPHONE, AapChannel.INPUT).forEach { channel ->
            val response = phone.openChannel(channel)
            assertEquals(0L, varints(response.body())[1])
        }
    }

    private fun discover() {
        phone.send(AapChannel.CONTROL, AapControl.SERVICE_DISCOVERY_REQUEST, ProtoWriter().string(5, "Pixel 9").toByteArray())
        val response = phone.readExpecting(AapChannel.CONTROL, AapControl.SERVICE_DISCOVERY_RESPONSE)
        assertTrue("post-handshake messages are encrypted", response.encrypted)
        assertEquals("Pixel 9", host.phoneNames.awaitNext())
    }

    @Test fun handshakeAuthenticatesWithTheProvisionedIdentity() {
        phone.connect()
        assertEquals(headIdentity.certificate, phone.tls.clientCertificate)
        discover()
    }

    @Test fun unknownChannelsAreRefused() {
        phone.connect()
        phone.send(9, AapControl.CHANNEL_OPEN_REQUEST, ProtoWriter().sint32(1, 0).int32(2, 9).toByteArray(), control = true)
        val response = phone.readExpecting(9, AapControl.CHANNEL_OPEN_RESPONSE)
        assertEquals(AapStatus.INVALID_SERVICE.toLong(), varints(response.body())[1]!!.toInt().toLong())
    }

    @Test fun sensorsReportDrivingStatusAndNightMode() {
        phone.connect()
        discover()
        openAllChannels()

        phone.send(AapChannel.SENSOR, AapSensorMessage.REQUEST,
            ProtoWriter().enum(1, AapSensorType.DRIVING_STATUS).int64(2, 0).toByteArray())
        assertEquals(0L, varints(phone.readExpecting(AapChannel.SENSOR, AapSensorMessage.RESPONSE).body())[1])
        val driving = phone.readExpecting(AapChannel.SENSOR, AapSensorMessage.BATCH)
        val drivingStatus = ProtoReader(driving.body()).let { it.next(); assertEquals(13, it.field); it.message() }
        assertTrue(drivingStatus.next())
        // Without a speed or parking signal the head unit asks the phone to apply its driving restrictions.
        assertEquals(AapDrivingStatus.NO_KEYBOARD_INPUT or AapDrivingStatus.LIMIT_MESSAGE_LENGTH, drivingStatus.int)
        assertTrue(drivingStatus.int and AapDrivingStatus.NO_VIDEO == 0)

        phone.send(AapChannel.SENSOR, AapSensorMessage.REQUEST,
            ProtoWriter().enum(1, AapSensorType.NIGHT_MODE).int64(2, 0).toByteArray())
        phone.readExpecting(AapChannel.SENSOR, AapSensorMessage.RESPONSE)
        val night = phone.readExpecting(AapChannel.SENSOR, AapSensorMessage.BATCH)
        val nightData = ProtoReader(night.body()).let { it.next(); assertEquals(10, it.field); it.message() }
        assertTrue(nightData.next()); assertTrue(nightData.bool) // host reports night

        session.setNightMode(false)
        val update = phone.readExpecting(AapChannel.SENSOR, AapSensorMessage.BATCH)
        val updated = ProtoReader(update.body()).let { it.next(); it.message() }
        assertTrue(updated.next()); assertFalse(updated.bool)

        phone.send(AapChannel.SENSOR, AapSensorMessage.REQUEST, ProtoWriter().enum(1, 99).int64(2, 0).toByteArray())
        assertEquals(AapStatus.INVALID_SENSOR, varints(phone.readExpecting(AapChannel.SENSOR, AapSensorMessage.RESPONSE).body())[1]!!.toInt())
    }

    @Test fun videoIsAckedAndFocusFollowsTheSurface() {
        phone.connect()
        discover()
        openAllChannels()

        phone.send(AapChannel.VIDEO, AapMedia.SETUP, ProtoWriter().enum(1, 3).toByteArray())
        val config = phone.readExpecting(AapChannel.VIDEO, AapMedia.CONFIG)
        val configValues = varints(config.body())
        assertEquals(2L, configValues[1]); assertEquals(1L, configValues[2])

        // The surface is not ready yet, so the head unit keeps the native UI and says so.
        phone.send(AapChannel.VIDEO, AapMedia.VIDEO_FOCUS_REQUEST, ProtoWriter().enum(2, AapFocus.VIDEO_PROJECTED).enum(3, 0).toByteArray())
        var focus = varints(phone.readExpecting(AapChannel.VIDEO, AapMedia.VIDEO_FOCUS_NOTIFICATION).body())
        assertEquals(AapFocus.VIDEO_NATIVE.toLong(), focus[1]); assertEquals(0L, focus[2] ?: 0L)
        assertTrue(host.projection.awaitNext())

        session.setProjectionVisible(true)
        focus = varints(phone.readExpecting(AapChannel.VIDEO, AapMedia.VIDEO_FOCUS_NOTIFICATION).body())
        assertEquals(AapFocus.VIDEO_PROJECTED.toLong(), focus[1]); assertEquals(1L, focus[2])

        phone.send(AapChannel.VIDEO, AapMedia.START, ProtoWriter().int32(1, 7).uint32(2, 0).toByteArray())
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f)
        phone.send(AapChannel.VIDEO, AapMedia.CODEC_CONFIG, sps)
        assertArrayEquals(sps, host.codecConfigs.awaitNext())
        var ack = varints(phone.readExpecting(AapChannel.VIDEO, AapMedia.ACK).body())
        assertEquals(7L, ack[1]); assertEquals(1L, ack[2])

        // A frame larger than one TLS record exercises fragmentation from the phone side.
        val frame = ByteArray(45_000) { (it * 7).toByte() }
        phone.send(AapChannel.VIDEO, AapMedia.DATA, AapCodec.mediaData(1234L, frame))
        val received = host.frames.awaitNext()
        assertEquals(1234L, received.first)
        assertArrayEquals(frame, received.second)
        ack = varints(phone.readExpecting(AapChannel.VIDEO, AapMedia.ACK).body())
        assertEquals(7L, ack[1])

        session.setProjectionVisible(false)
        focus = varints(phone.readExpecting(AapChannel.VIDEO, AapMedia.VIDEO_FOCUS_NOTIFICATION).body())
        assertEquals(AapFocus.VIDEO_NATIVE.toLong(), focus[1]); assertEquals(1L, focus[2])

        phone.send(AapChannel.VIDEO, AapMedia.STOP, ByteArray(0))
        host.videoStops.awaitNext()
    }

    @Test fun aPhoneThatLeavesProjectionIsReportedToThePlatform() {
        phone.connect()
        discover()
        openAllChannels()
        phone.send(AapChannel.VIDEO, AapMedia.VIDEO_FOCUS_REQUEST, ProtoWriter().enum(2, AapFocus.VIDEO_NATIVE).toByteArray())
        phone.readExpecting(AapChannel.VIDEO, AapMedia.VIDEO_FOCUS_NOTIFICATION)
        assertFalse(host.projection.awaitNext())
    }

    @Test fun audioStreamsPlayWithTheirDeclaredFormats() {
        phone.connect()
        discover()
        openAllChannels()
        val expected = mapOf(
            AapChannel.MEDIA_AUDIO to config.mediaAudio,
            AapChannel.SPEECH_AUDIO to config.speechAudio,
            AapChannel.SYSTEM_AUDIO to config.systemAudio,
        )
        for ((channel, format) in expected) {
            phone.send(channel, AapMedia.SETUP, ProtoWriter().enum(1, 1).toByteArray())
            phone.readExpecting(channel, AapMedia.CONFIG)
            phone.send(channel, AapMedia.START, ProtoWriter().int32(1, channel * 10).uint32(2, 0).toByteArray())
            val pcm = ByteArray(1920) { (channel + it).toByte() }
            phone.send(channel, AapMedia.DATA, AapCodec.mediaData(99L, pcm))
            val ack = varints(phone.readExpecting(channel, AapMedia.ACK).body())
            assertEquals((channel * 10).toLong(), ack[1])
            val sink = synchronized(host) { host.audio.getValue(channel) }
            val started = sink.formats.awaitNext()
            assertEquals(format.sampleRate, started.sampleRate)
            assertEquals(format.channels, started.channels)
            assertArrayEquals(pcm, sink.written.awaitNext())
        }
        phone.send(AapChannel.MEDIA_AUDIO, AapMedia.STOP, ByteArray(0))
        host.audio.getValue(AapChannel.MEDIA_AUDIO).stops.awaitNext()
    }

    @Test fun audioFocusIsGrantedAndReleased() {
        phone.connect()
        discover()
        phone.send(AapChannel.CONTROL, AapControl.AUDIO_FOCUS_REQUEST, ProtoWriter().enum(1, AapFocus.AUDIO_REQUEST_GAIN).toByteArray())
        assertEquals(AapFocus.AUDIO_STATE_GAIN.toLong(),
            varints(phone.readExpecting(AapChannel.CONTROL, AapControl.AUDIO_FOCUS_NOTIFICATION).body())[1])
        phone.send(AapChannel.CONTROL, AapControl.AUDIO_FOCUS_REQUEST, ProtoWriter().enum(1, AapFocus.AUDIO_REQUEST_RELEASE).toByteArray())
        assertEquals(AapFocus.AUDIO_STATE_LOSS.toLong(),
            varints(phone.readExpecting(AapChannel.CONTROL, AapControl.AUDIO_FOCUS_NOTIFICATION).body())[1])
        phone.send(AapChannel.CONTROL, AapControl.NAV_FOCUS_REQUEST, ProtoWriter().enum(1, AapFocus.NAV_PROJECTED).toByteArray())
        assertEquals(AapFocus.NAV_PROJECTED.toLong(),
            varints(phone.readExpecting(AapChannel.CONTROL, AapControl.NAV_FOCUS_NOTIFICATION).body())[1])
    }

    @Test fun pingsAreAnsweredWithTheSameTimestamp() {
        phone.connect()
        discover()
        phone.send(AapChannel.CONTROL, AapControl.PING_REQUEST, AapCodec.ping(987654321L))
        assertEquals(987654321L, AapCodec.parsePingTimestamp(phone.readExpecting(AapChannel.CONTROL, AapControl.PING_RESPONSE).body()))
    }

    @Test fun microphoneStreamsOnlyWhileTheCarMicIsOpen() {
        phone.connect()
        discover()
        openAllChannels()
        phone.send(AapChannel.MICROPHONE, AapMedia.SETUP, ProtoWriter().enum(1, 1).toByteArray())
        phone.readExpecting(AapChannel.MICROPHONE, AapMedia.CONFIG)

        phone.send(AapChannel.MICROPHONE, AapMedia.MICROPHONE_REQUEST, ProtoWriter().bool(1, true).int32(4, 1).toByteArray())
        val response = varints(phone.readExpecting(AapChannel.MICROPHONE, AapMedia.MICROPHONE_RESPONSE).body())
        assertEquals(0L, response[1])

        val pcm = ByteArray(640) { 3 }
        host.microphone.sink!!.invoke(pcm, pcm.size)
        val data = phone.readExpecting(AapChannel.MICROPHONE, AapMedia.DATA)
        assertTrue(data.encrypted)
        assertArrayEquals(pcm, data.body().copyOfRange(8, data.body().size))

        phone.send(AapChannel.MICROPHONE, AapMedia.MICROPHONE_REQUEST, ProtoWriter().bool(1, false).toByteArray())
        phone.readExpecting(AapChannel.MICROPHONE, AapMedia.MICROPHONE_RESPONSE)
        host.microphone.closed.awaitNext()
    }

    @Test fun touchAndKeysReachThePhoneOnceTheInputChannelIsOpen() {
        phone.connect()
        discover()
        session.sendTouch(AapPointerAction.DOWN, listOf(AapTouchPointer(0, 1, 1)), 0) // dropped: channel not open yet
        openAllChannels()
        phone.send(AapChannel.INPUT, AapInput.KEY_BINDING_REQUEST, ProtoWriter().packedInt32(1, listOf(85, 87)).toByteArray())
        assertEquals(0L, varints(phone.readExpecting(AapChannel.INPUT, AapInput.KEY_BINDING_RESPONSE).body())[1])

        session.sendTouch(AapPointerAction.DOWN, listOf(AapTouchPointer(0, 640, 360)), 0)
        val report = phone.readExpecting(AapChannel.INPUT, AapInput.INPUT_REPORT)
        val reader = ProtoReader(report.body())
        assertTrue(reader.next()); assertTrue("timestamp is set", reader.long > 0)
        assertTrue(reader.next()); assertEquals(3, reader.field)
        val touch = reader.message()
        assertTrue(touch.next()); assertEquals(1, touch.field)
        val pointer = touch.message()
        val coordinates = HashMap<Int, Int>()
        while (pointer.next()) coordinates[pointer.field] = pointer.int
        assertEquals(mapOf(1 to 640, 2 to 360, 3 to 0), coordinates)

        session.sendKey(85, true)
        val key = phone.readExpecting(AapChannel.INPUT, AapInput.INPUT_REPORT)
        assertTrue(key.body().isNotEmpty())
    }

    @Test fun phoneByeByeEndsTheSessionAfterAnAnswer() {
        phone.connect()
        discover()
        phone.send(AapChannel.CONTROL, AapControl.BYE_BYE_REQUEST, ProtoWriter().enum(1, 1).toByteArray())
        phone.readExpecting(AapChannel.CONTROL, AapControl.BYE_BYE_RESPONSE)
        assertEquals(AapEndReason.PHONE_DISCONNECTED, awaitEnd())
    }

    @Test fun carSideDisconnectAsksPolitelyThenEnds() {
        phone.connect()
        discover()
        session.disconnect()
        val request = phone.readExpecting(AapChannel.CONTROL, AapControl.BYE_BYE_REQUEST)
        assertEquals(1L, varints(request.body())[1])
        phone.send(AapChannel.CONTROL, AapControl.BYE_BYE_RESPONSE, ByteArray(0))
        assertEquals(AapEndReason.LOCAL_CLOSE, awaitEnd())
    }

    @Test fun anIncompatibleProtocolVersionEndsTheSessionBeforeTls() {
        phone.readExpecting(AapChannel.CONTROL, AapControl.VERSION_REQUEST)
        phone.send(AapChannel.CONTROL, AapControl.VERSION_RESPONSE, byteArrayOf(0, 1, 0, 0, -1, -1), encrypted = false)
        assertEquals(AapEndReason.INCOMPATIBLE_VERSION, awaitEnd())
    }

    @Test fun messagesBeforeAuthenticationAreAProtocolError() {
        phone.readExpecting(AapChannel.CONTROL, AapControl.VERSION_REQUEST)
        phone.send(AapChannel.CONTROL, AapControl.SERVICE_DISCOVERY_REQUEST, ByteArray(0), encrypted = false)
        assertEquals(AapEndReason.PROTOCOL_ERROR, awaitEnd())
    }

    @Test fun aClosedPhoneSocketEndsTheSession() {
        phone.connect()
        phone.close()
        assertEquals(AapEndReason.PHONE_DISCONNECTED, awaitEnd())
    }

    @Test fun closingFromTheCarUnblocksTheReader() {
        phone.connect()
        session.close()
        assertEquals(AapEndReason.LOCAL_CLOSE, awaitEnd())
    }

    @Test fun mediaOnAChannelThatWasNeverOpenedIsIgnoredNotFatal() {
        phone.connect()
        discover()
        phone.send(AapChannel.VIDEO, AapMedia.SETUP, ProtoWriter().enum(1, 3).toByteArray())
        phone.send(AapChannel.CONTROL, AapControl.PING_REQUEST, AapCodec.ping(7L))
        // The stray setup got no answer, and the session is still alive.
        assertEquals(7L, AapCodec.parsePingTimestamp(phone.readExpecting(AapChannel.CONTROL, AapControl.PING_RESPONSE).body()))
        assertTrue(host.logs.any { it.contains("not open") })
    }

    @Test fun aPingBeforeTheLinkIsEncryptedIsIgnored() {
        phone.readExpecting(AapChannel.CONTROL, AapControl.VERSION_REQUEST)
        phone.send(AapChannel.CONTROL, AapControl.PING_REQUEST, AapCodec.ping(1L), encrypted = false)
        phone.send(AapChannel.CONTROL, AapControl.VERSION_RESPONSE, byteArrayOf(0, 1, 0, 7, 0, 0), encrypted = false)
        // The handshake still proceeds after the stray ping.
        phone.readExpecting(AapChannel.CONTROL, AapControl.SSL_HANDSHAKE)
    }

    @Test fun videoFocusIsGrantedByTheHeadUnitWhenTheScreenIsReadyEvenIfThePhoneNeverAsked() {
        phone.connect()
        discover()
        openAllChannels()
        // Screen first, then setup: setup itself carries the grant.
        session.setProjectionVisible(true)
        phone.send(AapChannel.VIDEO, AapMedia.SETUP, ProtoWriter().enum(1, 3).toByteArray())
        phone.readExpecting(AapChannel.VIDEO, AapMedia.CONFIG)
        val focus = varints(phone.readExpecting(AapChannel.VIDEO, AapMedia.VIDEO_FOCUS_NOTIFICATION).body())
        assertEquals(AapFocus.VIDEO_PROJECTED.toLong(), focus[1])
        assertEquals(1L, focus[2])
    }

    @Test fun theScreenAppearingBeforeVideoSetupSendsNothingEarly() {
        phone.connect()
        discover()
        openAllChannels()
        session.setProjectionVisible(true)
        phone.send(AapChannel.CONTROL, AapControl.PING_REQUEST, AapCodec.ping(3L))
        // The first thing the phone sees is the ping answer, not a focus message for an unconfigured channel.
        assertEquals(AapControl.PING_RESPONSE, phone.read().messageId)
    }
}
