package com.shilapi.xcertplay.androidauto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtoWireTest {
    @Test fun encodesTheDocumentedExampleBytes() {
        // Field 1, varint 150 is the canonical example from the protobuf encoding guide.
        assertArrayEquals(byteArrayOf(0x08, 0x96.toByte(), 0x01), ProtoWriter().int32(1, 150).toByteArray())
        assertArrayEquals(byteArrayOf(0x12, 0x07, 't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
            'i'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte()), ProtoWriter().string(2, "testing").toByteArray())
    }

    @Test fun negativeInt32IsTenBytesAndRoundTrips() {
        val bytes = ProtoWriter().int32(1, -4).toByteArray()
        assertEquals(1 + 10, bytes.size)
        val reader = ProtoReader(bytes)
        assertTrue(reader.next())
        assertEquals(-4, reader.int)
    }

    @Test fun sint32UsesZigZag() {
        assertArrayEquals(byteArrayOf(0x08, 0x01), ProtoWriter().sint32(1, -1).toByteArray())
        for (value in listOf(0, 1, -1, 63, -64, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val reader = ProtoReader(ProtoWriter().sint32(1, value).toByteArray())
            assertTrue(reader.next())
            assertEquals(value, reader.sint32)
        }
    }

    @Test fun uint32KeepsTheUpperHalfOfTheRange() {
        val reader = ProtoReader(ProtoWriter().uint32(1, -1).toByteArray())
        assertTrue(reader.next())
        assertEquals(0xffffffffL, reader.long)
    }

    @Test fun nestedMessagesAndUnknownFieldsAreSkipped() {
        val bytes = ProtoWriter()
            .int32(1, 7)
            .message(2) { string(1, "inner"); bool(2, true) }
            .int64(99, 123456789012L)
            .bytes(100, byteArrayOf(1, 2, 3))
            .toByteArray()
        val reader = ProtoReader(bytes)
        assertTrue(reader.next())
        assertEquals(1, reader.field)
        assertEquals(7, reader.int)
        assertTrue(reader.next())
        val inner = reader.message()
        assertTrue(inner.next())
        assertEquals("inner", inner.string())
        assertTrue(inner.next())
        assertTrue(inner.bool)
        assertFalse(inner.next())
        assertTrue(reader.next())
        assertEquals(99, reader.field)
        assertEquals(123456789012L, reader.long)
        assertTrue(reader.next())
        assertArrayEquals(byteArrayOf(1, 2, 3), reader.bytes())
        assertFalse(reader.next())
    }

    @Test fun readsPackedAndUnpackedRepeatedInts() {
        val packed = ProtoWriter().packedInt32(1, listOf(4, 300, -1)).toByteArray()
        val values = ArrayList<Int>()
        ProtoReader(packed).apply { while (next()) addInt32s(values) }
        assertEquals(listOf(4, 300, -1), values)

        val unpacked = ProtoWriter().int32(1, 4).int32(1, 300).toByteArray()
        val more = ArrayList<Int>()
        ProtoReader(unpacked).apply { while (next()) addInt32s(more) }
        assertEquals(listOf(4, 300), more)
    }

    @Test fun readsFixedWidthFields() {
        val reader = ProtoReader(byteArrayOf(0x0d, 1, 0, 0, 0, 0x11, 2, 0, 0, 0, 0, 0, 0, 0))
        assertTrue(reader.next())
        assertEquals(1L, reader.long)
        assertTrue(reader.next())
        assertEquals(2L, reader.long)
    }

    @Test fun rejectsTruncatedAndUnsupportedInput() {
        for (broken in listOf(
            byteArrayOf(0x08),                       // varint with no value
            byteArrayOf(0x08, 0x80.toByte()),        // varint that never ends
            byteArrayOf(0x12, 0x05, 1, 2),           // length beyond the buffer
            byteArrayOf(0x0b),                       // start-group wire type
            byteArrayOf(0x0d, 1, 2),                 // truncated fixed32
            byteArrayOf(0x00, 0x00),                 // field number zero
        )) {
            try {
                ProtoReader(broken).apply { while (next()) Unit }
                fail("Expected ProtoException for ${broken.joinToString()}")
            } catch (_: ProtoException) {
            }
        }
    }

    @Test fun refusesAnInvalidFieldNumberWhenWriting() {
        try {
            ProtoWriter().int32(0, 1)
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun readerHonoursItsSubrange() {
        val buffer = byteArrayOf(0x7f, 0x08, 0x05, 0x7f)
        val reader = ProtoReader(buffer, 1, 2)
        assertTrue(reader.next())
        assertEquals(5, reader.int)
        assertFalse(reader.next())
    }
}
