package com.shilapi.xcertplay.androidauto

import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AapWirelessHandshakeTest {
    private val network = AapWifiNetwork(
        ssid = "DIRECT-DiPlay", passphrase = "correct horse", bssid = "02:11:22:33:44:55",
        security = AapWifiNetwork.Security.WPA2_PERSONAL, dynamic = true, hostAddress = "192.168.49.1",
    )

    private lateinit var link: LoopbackLink
    private val outcomes = LinkedBlockingQueue<Result<AapWifiOutcome>>()
    private val logs = LinkedBlockingQueue<String>()

    @Before fun setUp() {
        link = LoopbackLink()
        val handshake = AapWirelessHandshake(link.head.getInputStream(), link.head.getOutputStream()) { logs.add(it) }
        Thread({ outcomes.add(runCatching { handshake.run(network) }) }, "test-handshake").apply { isDaemon = true }.start()
    }

    @After fun tearDown() = link.close()

    private fun outcome(): Result<AapWifiOutcome> =
        outcomes.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("the handshake did not finish")

    private fun phoneSend(id: Int, body: ByteArray) {
        val frame = byteArrayOf((body.size ushr 8).toByte(), body.size.toByte(), (id ushr 8).toByte(), id.toByte()) + body
        link.phone.getOutputStream().apply { write(frame); flush() }
    }

    private fun phoneRead(): Pair<Int, ByteArray> {
        val input = DataInputStream(link.phone.getInputStream())
        val length = input.readUnsignedShort()
        val id = input.readUnsignedShort()
        return id to ByteArray(length).also { input.readFully(it) }
    }

    private fun fields(body: ByteArray): Map<Int, Any> {
        val reader = ProtoReader(body)
        val result = HashMap<Int, Any>()
        while (reader.next()) result[reader.field] = if (reader.isLengthDelimited) reader.string() else reader.long
        return result
    }

    @Test fun tellsThePhoneWhereToConnectThenGivesItTheNetwork() {
        val (startId, start) = phoneRead()
        assertEquals(AapWirelessHandshake.START_REQUEST, startId)
        assertEquals(mapOf<Int, Any>(1 to "192.168.49.1", 2 to 5288L), fields(start))

        phoneSend(AapWirelessHandshake.INFO_REQUEST, ByteArray(0))
        val (infoId, info) = phoneRead()
        assertEquals(AapWirelessHandshake.INFO_RESPONSE, infoId)
        assertEquals(
            mapOf<Int, Any>(1 to "DIRECT-DiPlay", 2 to "correct horse", 3 to "02:11:22:33:44:55", 4 to 5L, 5 to 1L),
            fields(info),
        )

        phoneSend(AapWirelessHandshake.START_RESPONSE, ProtoWriter().string(1, "192.168.49.1").uint32(2, 5288).enum(3, 0).toByteArray())
        phoneSend(AapWirelessHandshake.CONNECTION_STATUS, ProtoWriter().enum(1, 0).toByteArray())
        assertEquals(AapWifiOutcome.PHONE_JOINED, outcome().getOrThrow())
    }

    @Test fun aRefusalFromThePhoneIsReported() {
        phoneRead()
        phoneSend(AapWirelessHandshake.START_RESPONSE, ProtoWriter().enum(3, -3).toByteArray())
        assertEquals(AapWifiOutcome.PHONE_REFUSED, outcome().getOrThrow())
    }

    @Test fun aFailedJoinIsReported() {
        phoneRead()
        phoneSend(AapWirelessHandshake.START_RESPONSE, ProtoWriter().enum(3, 0).toByteArray())
        phoneSend(AapWirelessHandshake.CONNECTION_STATUS, ProtoWriter().enum(1, -11).string(2, "no network").toByteArray())
        assertEquals(AapWifiOutcome.PHONE_REFUSED, outcome().getOrThrow())
    }

    @Test fun aPhoneThatAcceptsAndHangsUpCountsAsAccepted() {
        phoneRead()
        phoneSend(AapWirelessHandshake.START_RESPONSE, ProtoWriter().enum(3, 0).toByteArray())
        link.phone.close()
        assertEquals(AapWifiOutcome.PHONE_ACCEPTED, outcome().getOrThrow())
    }

    @Test fun aLinkThatEndsBeforeAnyAnswerIsEarly() {
        phoneRead()
        link.phone.close()
        assertEquals(AapWifiOutcome.LINK_ENDED_EARLY, outcome().getOrThrow())
    }

    @Test fun aLinkCutInsideAMessageIsAnError() {
        phoneRead()
        link.phone.getOutputStream().apply { write(byteArrayOf(0, 9, 0, 7, 1, 2)); flush() }
        link.phone.close()
        try {
            outcome().getOrThrow()
            fail()
        } catch (_: IOException) {
        }
    }

    @Test fun unknownAndVersionMessagesDoNotDisturbTheExchange() {
        phoneRead()
        phoneSend(AapWirelessHandshake.VERSION_REQUEST, ByteArray(0))
        phoneSend(99, byteArrayOf(1, 2, 3))
        phoneSend(AapWirelessHandshake.CONNECTION_STATUS, ProtoWriter().enum(1, 0).toByteArray())
        assertEquals(AapWifiOutcome.PHONE_JOINED, outcome().getOrThrow())
        assertTrue(logs.any { it.contains("version") })
    }

    @Test fun networkValidationRejectsBadInput() {
        for (build in listOf<() -> Any>(
            { AapWifiNetwork("", "x", "02:11:22:33:44:55", AapWifiNetwork.Security.OPEN, false, "1.2.3.4") },
            { AapWifiNetwork("n", "x", "not-a-bssid", AapWifiNetwork.Security.OPEN, false, "1.2.3.4") },
            { AapWifiNetwork("n", "x", "02:11:22:33:44:55", AapWifiNetwork.Security.OPEN, false, "") },
        )) {
            try {
                build()
                fail()
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue(!network.toString().contains("correct horse"))
    }

    @Test fun theServiceUuidIsTheWellKnownWirelessOne() {
        assertEquals("4de17a00-52cb-11e6-bdf4-0800200c9a66", AapWireless.SERVICE_UUID.toString())
    }
}
