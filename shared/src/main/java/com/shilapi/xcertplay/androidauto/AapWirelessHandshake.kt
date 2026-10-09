package com.shilapi.xcertplay.androidauto

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Constants of the wireless Android Auto bootstrap. */
object AapWireless {
    /** RFCOMM service the phone looks for on a paired head unit before it offers wireless projection. */
    val SERVICE_UUID: UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")

    const val SERVICE_NAME = "Android Auto Wireless"

    /** TCP port the head unit listens on for the projection link. */
    const val TCP_PORT = AapSession.DEFAULT_PORT
}

/** The Wi-Fi network the head unit tells the phone to join. */
class AapWifiNetwork(
    val ssid: String,
    val passphrase: String,
    /** Colon-separated BSSID of the access point as the phone will see it. */
    val bssid: String,
    val security: Security,
    /** Dynamic networks change credentials between sessions; fixed ones can be remembered by the phone. */
    val dynamic: Boolean,
    /** IPv4 address the head unit listens on, in the network the phone will join. */
    val hostAddress: String,
) {
    enum class Security(val code: Int) { OPEN(1), WPA_PERSONAL(4), WPA2_PERSONAL(5), WPA_WPA2_PERSONAL(6) }

    init {
        require(ssid.isNotEmpty()) { "A Wi-Fi network needs an SSID" }
        require(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}").matches(bssid)) { "BSSID must be six colon-separated bytes" }
        require(hostAddress.isNotEmpty()) { "The host address is required" }
    }

    override fun toString(): String = "AapWifiNetwork(ssid='$ssid', passphrase=<redacted>, bssid=$bssid, security=$security, host=$hostAddress)"
}

/** How the Bluetooth bootstrap ended. */
enum class AapWifiOutcome {
    /** The phone reported that it joined the network. */
    PHONE_JOINED,

    /** The phone accepted the plan and closed the Bluetooth link without a further report. */
    PHONE_ACCEPTED,

    /** The phone could not join, or refused the plan. */
    PHONE_REFUSED,

    /** The Bluetooth link ended before the phone accepted the plan. */
    LINK_ENDED_EARLY,
}

/**
 * The Bluetooth half of wireless Android Auto.
 *
 * Once a paired phone opens the Android Auto RFCOMM service, the head unit tells it where to
 * connect (`WifiStartRequest`), answers the phone's request for network credentials
 * (`WifiInfoRequest`) with the Wi-Fi network it created, and then waits for the phone to say it
 * joined. The projection link itself runs over TCP afterwards.
 *
 * Messages are `[payload length: 2][message id: 2][protobuf payload]`, big endian.
 */
class AapWirelessHandshake(
    private val input: InputStream,
    private val output: OutputStream,
    private val log: (String) -> Unit = {},
) {
    /** Blocks until the phone's answer is known. Call it from a worker thread. */
    @Throws(IOException::class)
    fun run(network: AapWifiNetwork, port: Int = AapWireless.TCP_PORT): AapWifiOutcome {
        send(START_REQUEST, ProtoWriter().string(1, network.hostAddress).uint32(2, port).toByteArray())
        var accepted = false
        while (true) {
            val message = try {
                read()
            } catch (_: EOFException) {
                return if (accepted) AapWifiOutcome.PHONE_ACCEPTED else AapWifiOutcome.LINK_ENDED_EARLY
            }
            when (message.id) {
                INFO_REQUEST -> {
                    log("Phone asked for the Wi-Fi network")
                    send(INFO_RESPONSE, infoResponse(network))
                }
                START_RESPONSE -> {
                    val status = statusOf(message.body, statusField = 3)
                    log("Phone answered the start request with status $status")
                    if (status != STATUS_SUCCESS) return AapWifiOutcome.PHONE_REFUSED
                    accepted = true
                }
                CONNECTION_STATUS -> {
                    val status = statusOf(message.body, statusField = 1)
                    log("Phone reports Wi-Fi status $status")
                    return if (status == STATUS_SUCCESS) AapWifiOutcome.PHONE_JOINED else AapWifiOutcome.PHONE_REFUSED
                }
                VERSION_REQUEST -> log("Phone asked for the wireless protocol version; not answered")
                else -> log("Ignored wireless bootstrap message id ${message.id}")
            }
        }
    }

    private class Message(val id: Int, val body: ByteArray)

    private fun infoResponse(network: AapWifiNetwork): ByteArray = ProtoWriter()
        .string(1, network.ssid)
        .string(2, network.passphrase)
        .string(3, network.bssid)
        .enum(4, network.security.code)
        .enum(5, if (network.dynamic) ACCESS_POINT_DYNAMIC else ACCESS_POINT_STATIC)
        .toByteArray()

    private fun statusOf(body: ByteArray, statusField: Int): Int {
        val reader = ProtoReader(body)
        while (reader.next()) if (reader.field == statusField && !reader.isLengthDelimited) return reader.int
        return STATUS_MISSING
    }

    private fun send(id: Int, body: ByteArray) {
        if (body.size > 0xffff) throw IOException("Bootstrap message is too large")
        val frame = ByteArray(4 + body.size)
        frame[0] = (body.size ushr 8).toByte()
        frame[1] = body.size.toByte()
        frame[2] = (id ushr 8).toByte()
        frame[3] = id.toByte()
        body.copyInto(frame, 4)
        output.write(frame)
        output.flush()
    }

    private fun read(): Message {
        val header = readExactly(4, firstRead = true)
        val length = ((header[0].toInt() and 0xff) shl 8) or (header[1].toInt() and 0xff)
        val id = ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
        return Message(id, if (length == 0) ByteArray(0) else readExactly(length, firstRead = false))
    }

    private fun readExactly(size: Int, firstRead: Boolean): ByteArray {
        val data = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(data, read, size - read)
            if (count < 0) {
                // A phone that is finished may close between messages; that is not an error.
                if (firstRead && read == 0) throw EOFException("Bootstrap link closed")
                throw IOException("Bootstrap link closed in the middle of a message")
            }
            read += count
        }
        return data
    }

    companion object {
        const val START_REQUEST = 1
        const val INFO_REQUEST = 2
        const val INFO_RESPONSE = 3
        const val VERSION_REQUEST = 4
        const val VERSION_RESPONSE = 5
        const val CONNECTION_STATUS = 6
        const val START_RESPONSE = 7

        private const val STATUS_SUCCESS = 0
        private const val STATUS_MISSING = -1
        private const val ACCESS_POINT_STATIC = 0
        private const val ACCESS_POINT_DYNAMIC = 1
    }
}
