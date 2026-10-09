package com.shilapi.xcertplay.androidauto

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Encrypts and decrypts the payload of one frame; implemented by the TLS layer. */
interface AapCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(record: ByteArray): ByteArray
}

/** A protocol violation on the Android Auto link. */
class AapProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * One complete Android Auto message after reassembly and decryption.
 *
 * [payload] starts with the big-endian 16-bit message id, followed by the message body.
 * [control] is the header's control flag, which on non-zero channels marks the shared control
 * message ids (for example a channel open request) as opposed to that channel's own ids.
 */
class AapMessage(
    val channel: Int,
    val encrypted: Boolean,
    val control: Boolean,
    val payload: ByteArray,
) {
    init {
        require(channel in 0..255) { "Channel id must fit one byte" }
        require(payload.size >= MESSAGE_ID_SIZE) { "A message needs a 16-bit id" }
    }

    val messageId: Int get() = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)

    fun body(): ByteArray = payload.copyOfRange(MESSAGE_ID_SIZE, payload.size)

    override fun toString(): String =
        "AapMessage(channel=$channel, id=0x${messageId.toString(16)}, encrypted=$encrypted, " +
            "control=$control, bodyBytes=${payload.size - MESSAGE_ID_SIZE})"

    companion object {
        const val MESSAGE_ID_SIZE = 2

        fun build(channel: Int, encrypted: Boolean, control: Boolean, messageId: Int, body: ByteArray): AapMessage {
            val payload = ByteArray(MESSAGE_ID_SIZE + body.size)
            payload[0] = (messageId ushr 8).toByte()
            payload[1] = messageId.toByte()
            body.copyInto(payload, MESSAGE_ID_SIZE)
            return AapMessage(channel, encrypted, control, payload)
        }
    }
}

object AapFrameFlags {
    const val FIRST = 0x01
    const val LAST = 0x02
    const val BULK = FIRST or LAST
    const val CONTROL = 0x04
    const val ENCRYPTED = 0x08

    /** A payload of at least this size is split into several frames. */
    const val MAX_FRAME_PAYLOAD = 0x4000

    /** Upper bound on one reassembled message, so a faulty peer cannot exhaust memory. */
    const val MAX_MESSAGE_SIZE = 4 * 1024 * 1024
}

/**
 * Writes messages as Android Auto frames: `[channel][flags][size:2]`, plus a 4-byte total size on
 * the first frame of a split message. Each fragment is encrypted on its own and the size field
 * counts the bytes on the wire.
 */
class AapFrameWriter(private val output: OutputStream) {
    private val lock = Any()

    @Volatile var cipher: AapCipher? = null

    @Throws(IOException::class)
    fun write(message: AapMessage) {
        val plain = message.payload
        // Encryption order must equal wire order, so encrypt and write under one lock.
        synchronized(lock) {
            var offset = 0
            while (true) {
                val chunkSize = minOf(AapFrameFlags.MAX_FRAME_PAYLOAD, plain.size - offset)
                val first = offset == 0
                val last = offset + chunkSize >= plain.size
                var flags = (if (first) AapFrameFlags.FIRST else 0) or (if (last) AapFrameFlags.LAST else 0)
                if (message.control) flags = flags or AapFrameFlags.CONTROL
                var chunk = plain.copyOfRange(offset, offset + chunkSize)
                if (message.encrypted) {
                    flags = flags or AapFrameFlags.ENCRYPTED
                    val active = cipher ?: throw AapProtocolException("Encrypted frame before the TLS handshake")
                    chunk = active.encrypt(chunk)
                }
                if (chunk.size > 0xffff) throw AapProtocolException("Frame payload is too large")
                // Only the first frame of a split message announces the total size.
                val extended = first && !last
                val header = ByteArray(if (extended) 8 else 4)
                header[0] = message.channel.toByte()
                header[1] = flags.toByte()
                header[2] = (chunk.size ushr 8).toByte()
                header[3] = chunk.size.toByte()
                if (extended) {
                    header[4] = (plain.size ushr 24).toByte()
                    header[5] = (plain.size ushr 16).toByte()
                    header[6] = (plain.size ushr 8).toByte()
                    header[7] = plain.size.toByte()
                }
                output.write(header)
                output.write(chunk)
                offset += chunkSize
                if (last) break
            }
            output.flush()
        }
    }
}

/** Reads frames, reassembles split messages per channel and decrypts them. */
class AapFrameReader(private val input: InputStream) {
    @Volatile var cipher: AapCipher? = null

    private class Partial(val encrypted: Boolean, val control: Boolean, val expected: Int) {
        val data = ByteArrayOutputStream()
    }

    private val partials = HashMap<Int, Partial>()

    /** Blocks for the next complete message. Returns null when the peer closes between frames. */
    @Throws(IOException::class)
    fun read(): AapMessage? {
        while (true) {
            val header = readExactly(4, allowCleanEnd = true) ?: return null
            val channel = header[0].toInt() and 0xff
            val flags = header[1].toInt() and 0xff
            val frameType = flags and AapFrameFlags.BULK
            val size = ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
            val encrypted = flags and AapFrameFlags.ENCRYPTED != 0
            val control = flags and AapFrameFlags.CONTROL != 0
            // Only a first frame that is not also the last carries the 4-byte total size.
            var total = size
            if (frameType == AapFrameFlags.FIRST) {
                val extra = readExactly(4, allowCleanEnd = false)!!
                total = ((extra[0].toInt() and 0xff) shl 24) or ((extra[1].toInt() and 0xff) shl 16) or
                    ((extra[2].toInt() and 0xff) shl 8) or (extra[3].toInt() and 0xff)
                if (total < AapMessage.MESSAGE_ID_SIZE || total > AapFrameFlags.MAX_MESSAGE_SIZE) {
                    throw AapProtocolException("Message size $total is outside the accepted range")
                }
            }
            val wire = readExactly(size, allowCleanEnd = false)!!
            val plain = if (encrypted) {
                val active = cipher ?: throw AapProtocolException("Encrypted frame before the TLS handshake")
                try {
                    active.decrypt(wire)
                } catch (error: RuntimeException) {
                    throw AapProtocolException("Could not decrypt a frame", error)
                }
            } else {
                wire
            }

            when (frameType) {
                AapFrameFlags.BULK -> {
                    partials.remove(channel)
                    return complete(channel, encrypted, control, plain)
                }
                AapFrameFlags.FIRST -> {
                    val partial = Partial(encrypted, control, total)
                    appendBounded(partial, plain)
                    partials[channel] = partial
                }
                else -> {
                    val partial = partials[channel]
                        ?: throw AapProtocolException("Frame continuation without a first frame on channel $channel")
                    appendBounded(partial, plain)
                    if (frameType == AapFrameFlags.LAST) {
                        partials.remove(channel)
                        if (partial.data.size() != partial.expected) {
                            throw AapProtocolException(
                                "Message on channel $channel has ${partial.data.size()} bytes, expected ${partial.expected}",
                            )
                        }
                        return complete(channel, partial.encrypted, partial.control, partial.data.toByteArray())
                    }
                }
            }
        }
    }

    private fun appendBounded(partial: Partial, chunk: ByteArray) {
        if (partial.data.size() + chunk.size > partial.expected) {
            throw AapProtocolException("Message is longer than its announced size ${partial.expected}")
        }
        partial.data.write(chunk, 0, chunk.size)
    }

    private fun complete(channel: Int, encrypted: Boolean, control: Boolean, payload: ByteArray): AapMessage {
        if (payload.size < AapMessage.MESSAGE_ID_SIZE) throw AapProtocolException("Message without an id on channel $channel")
        return AapMessage(channel, encrypted, control, payload)
    }

    private fun readExactly(size: Int, allowCleanEnd: Boolean): ByteArray? {
        val data = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(data, read, size - read)
            if (count < 0) {
                if (read == 0 && allowCleanEnd) return null
                throw EOFException("Android Auto link closed in the middle of a frame")
            }
            read += count
        }
        return data
    }
}
