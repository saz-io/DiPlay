package com.shilapi.xcertplay.androidauto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AapFrameTest {
    /** Not real encryption: it only makes the framing observable (a 3-byte tag per frame). */
    private val tagging = object : AapCipher {
        override fun encrypt(plain: ByteArray) = byteArrayOf(9, 9, 9) + plain
        override fun decrypt(record: ByteArray): ByteArray {
            assertEquals(listOf<Byte>(9, 9, 9), record.take(3))
            return record.copyOfRange(3, record.size)
        }
    }

    private fun write(message: AapMessage, cipher: AapCipher? = null): ByteArray {
        val out = ByteArrayOutputStream()
        AapFrameWriter(out).apply { this.cipher = cipher }.write(message)
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray, cipher: AapCipher? = null): List<AapMessage> {
        val reader = AapFrameReader(ByteArrayInputStream(bytes)).apply { this.cipher = cipher }
        return generateSequence { reader.read() }.toList()
    }

    @Test fun smallMessageIsOneBulkFrameWithTheDocumentedHeader() {
        val message = AapMessage.build(0, false, false, AapControl.VERSION_REQUEST, AapCodec.versionRequest(1, 1))
        // channel 0, flags FIRST|LAST, size 6, then id 0x0001 and major/minor.
        assertArrayEquals(byteArrayOf(0, 3, 0, 6, 0, 1, 0, 1, 0, 1), write(message))
    }

    @Test fun controlAndEncryptedFlagsAreCarriedInTheHeader() {
        val bytes = write(AapMessage.build(3, true, true, 7, byteArrayOf(5)), tagging)
        assertEquals(3, bytes[0].toInt())
        assertEquals(0x03 or 0x04 or 0x08, bytes[1].toInt())
        assertEquals(3 + 3, ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff))
        val message = read(bytes, tagging).single()
        assertTrue(message.encrypted)
        assertTrue(message.control)
        assertEquals(7, message.messageId)
        assertArrayEquals(byteArrayOf(5), message.body())
    }

    @Test fun largeMessageIsSplitAndReassembled() {
        val body = ByteArray(40_000) { (it * 31).toByte() }
        val bytes = write(AapMessage.build(3, false, false, AapMedia.DATA, body))
        // 40_002 payload bytes: FIRST(0x4000) + MIDDLE(0x4000) + LAST(rest).
        assertEquals(0x01, bytes[1].toInt() and 0x03)
        val firstSize = ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
        assertEquals(0x4000, firstSize)
        val total = ((bytes[4].toInt() and 0xff) shl 24) or ((bytes[5].toInt() and 0xff) shl 16) or
            ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
        assertEquals(40_002, total)
        val middle = 8 + 0x4000
        assertEquals(0x00, bytes[middle + 1].toInt() and 0x03)
        val last = middle + 4 + 0x4000
        assertEquals(0x02, bytes[last + 1].toInt() and 0x03)

        val message = read(bytes).single()
        assertEquals(AapMedia.DATA, message.messageId)
        assertArrayEquals(body, message.body())
    }

    @Test fun splitMessagesAreEncryptedPerFragment() {
        val body = ByteArray(20_000) { it.toByte() }
        val bytes = write(AapMessage.build(4, true, false, AapMedia.DATA, body), tagging)
        // Each fragment carries the cipher's 3-byte tag, so the first frame is 0x4000 + 3 on the wire.
        assertEquals(0x4003, ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff))
        assertArrayEquals(body, read(bytes, tagging).single().body())
    }

    @Test fun framesOfDifferentChannelsMayInterleave() {
        val big = ByteArray(30_000) { 1 }
        val bigFrames = write(AapMessage.build(3, false, false, AapMedia.DATA, big))
        val split = 8 + 0x4000
        val small = write(AapMessage.build(0, false, false, AapControl.PING_REQUEST, byteArrayOf(8, 1)))
        val interleaved = bigFrames.copyOfRange(0, split) + small + bigFrames.copyOfRange(split, bigFrames.size)
        val messages = read(interleaved)
        assertEquals(listOf(0, 3), messages.map { it.channel })
        assertArrayEquals(big, messages[1].body())
    }

    @Test fun cleanEndBetweenFramesIsNullButEndInsideAFrameFails() {
        val bytes = write(AapMessage.build(0, false, false, 1, byteArrayOf(1)))
        assertNull(AapFrameReader(ByteArrayInputStream(ByteArray(0))).read())
        try {
            AapFrameReader(ByteArrayInputStream(bytes.copyOf(bytes.size - 1))).read()
            fail()
        } catch (_: EOFException) {
        }
    }

    @Test fun rejectsContinuationWithoutAFirstFrame() {
        val orphan = byteArrayOf(3, 0x02, 0, 3, 0, 1, 7)
        try {
            read(orphan)
            fail()
        } catch (_: AapProtocolException) {
        }
    }

    @Test fun rejectsAnAbsurdAnnouncedSize() {
        val header = byteArrayOf(3, 0x01, 0, 2, 0x7f, 0, 0, 0, 0, 1)
        try {
            read(header)
            fail()
        } catch (_: AapProtocolException) {
        }
    }

    @Test fun rejectsAFragmentThatOverrunsItsAnnouncedSize() {
        // FIRST announces 4 bytes but carries 5.
        val frame = byteArrayOf(3, 0x01, 0, 5, 0, 0, 0, 4, 0, 1, 2, 3, 4)
        try {
            read(frame)
            fail()
        } catch (_: AapProtocolException) {
        }
    }

    @Test fun rejectsEncryptedFramesBeforeTheCipherExists() {
        val bytes = byteArrayOf(0, 0x0b, 0, 3, 0, 1, 7)
        try {
            read(bytes)
            fail()
        } catch (_: AapProtocolException) {
        }
        try {
            write(AapMessage.build(0, true, false, 1, byteArrayOf()))
            fail()
        } catch (_: AapProtocolException) {
        }
    }

    @Test fun messageHelpersExposeIdAndBody() {
        val message = AapMessage.build(2, false, false, 0x8001, byteArrayOf(1, 2))
        assertEquals(0x8001, message.messageId)
        assertArrayEquals(byteArrayOf(1, 2), message.body())
        assertFalse(message.toString().contains("[B@"))
    }
}
