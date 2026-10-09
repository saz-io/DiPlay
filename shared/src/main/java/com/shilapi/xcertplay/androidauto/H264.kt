package com.shilapi.xcertplay.androidauto

/** Annex B helpers for the H.264 the phone sends. */
internal object H264 {
    private const val NAL_IDR = 5
    private const val NAL_SPS = 7
    private const val NAL_PPS = 8

    /** One NAL unit with its start code, as MediaCodec's csd-0 and csd-1 buffers expect. */
    class ParameterSets(val sps: ByteArray?, val pps: ByteArray?)

    /** Splits a codec config buffer into its SPS and PPS NAL units, each keeping its start code. */
    fun parameterSets(config: ByteArray): ParameterSets {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        forEachNal(config, 0, config.size) { type, start, end ->
            when (type) {
                NAL_SPS -> if (sps == null) sps = config.copyOfRange(start, end)
                NAL_PPS -> if (pps == null) pps = config.copyOfRange(start, end)
            }
            false
        }
        return ParameterSets(sps, pps)
    }

    /** True when the access unit holds an IDR slice, the only frame a decoder can start from. */
    fun containsIdr(data: ByteArray, offset: Int, length: Int): Boolean =
        forEachNal(data, offset, offset + length) { type, _, _ -> type == NAL_IDR }

    /**
     * Calls [visit] with each NAL unit's type and its byte range, including the start code, in
     * order. Stops and returns true when [visit] returns true.
     */
    private inline fun forEachNal(data: ByteArray, from: Int, to: Int, visit: (type: Int, start: Int, end: Int) -> Boolean): Boolean {
        var index = from
        var nalStart = -1
        var codeStart = -1
        while (index + 2 < to) {
            if (data[index].toInt() == 0 && data[index + 1].toInt() == 0 && data[index + 2].toInt() == 1) {
                if (nalStart >= 0 && visit(data[nalStart].toInt() and 0x1f, codeStart, trimZeros(data, codeStart, index))) return true
                // A 4-byte start code has one more leading zero than a 3-byte one.
                codeStart = if (index > from && data[index - 1].toInt() == 0) index - 1 else index
                nalStart = index + 3
                index += 3
            } else {
                index++
            }
        }
        if (nalStart in from until to && visit(data[nalStart].toInt() and 0x1f, codeStart, to)) return true
        return false
    }

    /** End of a NAL unit that is followed by a start code at [nextStartCode]: drop the zero that makes a 4-byte code. */
    private fun trimZeros(data: ByteArray, from: Int, nextStartCode: Int): Int =
        if (nextStartCode > from && data[nextStartCode - 1].toInt() == 0) nextStartCode - 1 else nextStartCode
}
