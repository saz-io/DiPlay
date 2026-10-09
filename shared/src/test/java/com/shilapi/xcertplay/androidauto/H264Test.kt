package com.shilapi.xcertplay.androidauto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H264Test {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val sps = bytes(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f, 0xe9)
    private val pps = bytes(0, 0, 0, 1, 0x68, 0xce, 0x06, 0xe2)

    @Test fun splitsSpsAndPpsKeepingTheirStartCodes() {
        val sets = H264.parameterSets(sps + pps)
        assertArrayEquals(sps, sets.sps)
        assertArrayEquals(pps, sets.pps)
    }

    @Test fun acceptsThreeByteStartCodesAndAnyOrder() {
        val sps3 = bytes(0, 0, 1, 0x67, 0x42, 0x00)
        val pps3 = bytes(0, 0, 1, 0x68, 0xce)
        val sets = H264.parameterSets(pps3 + sps3)
        assertArrayEquals(sps3, sets.sps)
        assertArrayEquals(pps3, sets.pps)
    }

    @Test fun reportsMissingParameterSets() {
        val onlySps = H264.parameterSets(sps)
        assertArrayEquals(sps, onlySps.sps)
        assertNull(onlySps.pps)
        val none = H264.parameterSets(bytes(1, 2, 3))
        assertNull(none.sps); assertNull(none.pps)
    }

    @Test fun detectsIdrSlicesAnywhereInTheAccessUnit() {
        val idr = bytes(0, 0, 0, 1, 0x65, 0x88, 0x84, 0x00)
        val pFrame = bytes(0, 0, 0, 1, 0x41, 0x9a, 0x24)
        assertTrue(H264.containsIdr(idr, 0, idr.size))
        assertFalse(H264.containsIdr(pFrame, 0, pFrame.size))
        val withConfigFirst = sps + pps + idr
        assertTrue(H264.containsIdr(withConfigFirst, 0, withConfigFirst.size))
        val sei = bytes(0, 0, 1, 0x06, 0x05, 0x01) + pFrame
        assertFalse(H264.containsIdr(sei, 0, sei.size))
    }

    @Test fun honoursTheGivenRange() {
        val data = bytes(9, 9) + bytes(0, 0, 0, 1, 0x65, 0x01) + bytes(9)
        assertTrue(H264.containsIdr(data, 2, 6))
        assertFalse(H264.containsIdr(data, 0, 2))
        assertFalse(H264.containsIdr(ByteArray(0), 0, 0))
    }

    @Test fun anEmbeddedZeroRunInPayloadIsNotAStartCode() {
        val frame = bytes(0, 0, 0, 1, 0x41, 0x00, 0x00, 0x03, 0x00, 0x00, 0x03, 0x55)
        assertFalse(H264.containsIdr(frame, 0, frame.size))
    }
}
