package com.shilapi.xcertplay.androidauto

import java.io.ByteArrayOutputStream
import java.io.IOException

/** A protobuf payload that is truncated or uses a wire type this receiver does not accept. */
class ProtoException(message: String) : IOException(message)

/**
 * Minimal proto2 encoder for the Android Auto messages this receiver sends.
 *
 * Negative `int32` values are written as sign-extended 64-bit varints, as protobuf requires, so
 * they are interchangeable with the status codes the phone expects (for example `-1`).
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun int32(field: Int, value: Int): ProtoWriter = varintField(field, value.toLong())

    fun uint32(field: Int, value: Int): ProtoWriter = varintField(field, value.toLong() and 0xffffffffL)

    fun int64(field: Int, value: Long): ProtoWriter = varintField(field, value)

    fun sint32(field: Int, value: Int): ProtoWriter =
        varintField(field, ((value shl 1) xor (value shr 31)).toLong() and 0xffffffffL)

    fun bool(field: Int, value: Boolean): ProtoWriter = varintField(field, if (value) 1L else 0L)

    fun enum(field: Int, value: Int): ProtoWriter = int32(field, value)

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        writeTag(field, WIRE_LENGTH_DELIMITED)
        writeVarint(value.size.toLong())
        out.write(value, 0, value.size)
        return this
    }

    /** Writes a nested message built by [build]. An empty message is still written. */
    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(build).toByteArray())

    /** Writes a `[packed = true]` repeated int32 field. Nothing is written for an empty list. */
    fun packedInt32(field: Int, values: List<Int>): ProtoWriter {
        if (values.isEmpty()) return this
        val packed = ByteArrayOutputStream()
        for (value in values) {
            var x = value.toLong()
            while (x and 0x7fL.inv() != 0L) {
                packed.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
            packed.write(x.toInt())
        }
        return bytes(field, packed.toByteArray())
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun varintField(field: Int, value: Long): ProtoWriter {
        writeTag(field, WIRE_VARINT)
        writeVarint(value)
        return this
    }

    private fun writeTag(field: Int, wireType: Int) {
        require(field in 1..MAX_FIELD) { "Invalid protobuf field number $field" }
        writeVarint(((field shl 3) or wireType).toLong())
    }

    private fun writeVarint(value: Long) {
        var x = value
        while (x and 0x7fL.inv() != 0L) {
            out.write(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
        out.write(x.toInt())
    }

    private companion object {
        const val MAX_FIELD = (1 shl 29) - 1
    }
}

/**
 * Minimal proto2 reader. [next] consumes one whole field, so unknown fields are skipped without
 * any extra call. The accessors describe the field [next] just returned.
 */
class ProtoReader(private val buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
    private var position: Int
    private val end: Int
    private var valueStart = 0
    private var valueEnd = 0

    /** Field number of the current field. */
    var field = 0
        private set

    /** Protobuf wire type of the current field. */
    var wireType = 0
        private set

    /** Value of a varint, fixed32 or fixed64 field. */
    var long = 0L
        private set

    init {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) { "Invalid reader range" }
        position = offset
        end = offset + length
    }

    val int: Int get() = long.toInt()

    val bool: Boolean get() = long != 0L

    /** The value of a `sint32` field. */
    val sint32: Int get() = (long.toInt() ushr 1) xor -(long.toInt() and 1)

    val isLengthDelimited: Boolean get() = wireType == WIRE_LENGTH_DELIMITED

    @Throws(ProtoException::class)
    fun next(): Boolean {
        if (position >= end) return false
        val key = readVarint()
        val number = (key ushr 3)
        if (number < 1 || number > Int.MAX_VALUE) throw ProtoException("Invalid protobuf field number")
        field = number.toInt()
        wireType = (key and 7).toInt()
        when (wireType) {
            WIRE_VARINT -> long = readVarint()
            WIRE_FIXED64 -> long = readFixed(8)
            WIRE_FIXED32 -> long = readFixed(4)
            WIRE_LENGTH_DELIMITED -> {
                val size = readVarint()
                if (size < 0 || size > end - position) throw ProtoException("Truncated protobuf field")
                valueStart = position
                valueEnd = position + size.toInt()
                position = valueEnd
            }
            else -> throw ProtoException("Unsupported protobuf wire type $wireType")
        }
        return true
    }

    fun bytes(): ByteArray {
        check(isLengthDelimited) { "Field $field is not length-delimited" }
        return buffer.copyOfRange(valueStart, valueEnd)
    }

    fun string(): String {
        check(isLengthDelimited) { "Field $field is not length-delimited" }
        return String(buffer, valueStart, valueEnd - valueStart, Charsets.UTF_8)
    }

    fun message(): ProtoReader {
        check(isLengthDelimited) { "Field $field is not length-delimited" }
        return ProtoReader(buffer, valueStart, valueEnd - valueStart)
    }

    /**
     * Appends the current field's int32 values to [into]. Accepts both the packed encoding and
     * the one-value-per-tag encoding, because senders may use either.
     */
    fun addInt32s(into: MutableList<Int>) {
        if (wireType == WIRE_VARINT) {
            into += long.toInt()
            return
        }
        check(isLengthDelimited) { "Field $field is not an int32 list" }
        val packed = ProtoReader(buffer, valueStart, valueEnd - valueStart)
        while (packed.position < packed.end) into += packed.readVarint().toInt()
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (position >= end) throw ProtoException("Truncated protobuf varint")
            val b = buffer[position++].toInt()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw ProtoException("Protobuf varint is too long")
    }

    private fun readFixed(size: Int): Long {
        if (end - position < size) throw ProtoException("Truncated protobuf fixed field")
        var result = 0L
        for (index in 0 until size) result = result or ((buffer[position++].toLong() and 0xff) shl (8 * index))
        return result
    }
}

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH_DELIMITED = 2
private const val WIRE_FIXED32 = 5
