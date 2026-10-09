package com.shilapi.xcertplay.androidauto

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Decodes the phone's H.264 stream onto a [Surface].
 *
 * The decoder starts when it has both the codec configuration and a surface, and keeps running
 * while the surface is replaced. With no usable surface it keeps decoding but does not render,
 * which is what the platform requires after a surface is destroyed.
 */
class AapVideoDecoder(
    private val width: Int,
    private val height: Int,
    private val log: (String) -> Unit = {},
) : AapVideoSink {
    private val lock = Any()
    private val renderLock = Any()

    private var surface: Surface? = null
    private var config: ByteArray? = null
    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null
    private var waitingForKeyFrame = true
    private var framesDroppedWaiting = 0
    private var inputDrops = 0

    @Volatile private var rendering = false
    @Volatile private var generation = 0

    /** Hands the decoder a new output surface, or null when the surface is gone. */
    fun setSurface(newSurface: Surface?) {
        val usable = newSurface?.takeIf { it.isValid }
        if (usable == null) {
            // Rendering must have stopped before the surface callback returns.
            synchronized(renderLock) { rendering = false }
        }
        synchronized(lock) {
            surface = usable
            val active = codec
            if (usable == null) return
            if (active == null) {
                startLocked()
                return
            }
            try {
                active.setOutputSurface(usable)
                synchronized(renderLock) { rendering = true }
            } catch (error: RuntimeException) {
                log("Video surface swap failed (${error.javaClass.simpleName}); restarting the decoder")
                stopLocked()
                startLocked()
            }
        }
    }

    override fun onCodecConfig(data: ByteArray) {
        synchronized(lock) {
            if (codec != null && config?.contentEquals(data) == true) return
            config = data.copyOf()
            if (codec != null) stopLocked()
            startLocked()
        }
    }

    override fun onFrame(timestampMicros: Long, data: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            val active = codec ?: return
            if (waitingForKeyFrame) {
                if (H264.containsIdr(data, offset, length)) {
                    waitingForKeyFrame = false
                } else if (++framesDroppedWaiting < MAX_FRAMES_WAITING_FOR_KEY) {
                    return
                } else {
                    // Some encoders recover with intra refresh instead of IDR frames; do not wait forever.
                    waitingForKeyFrame = false
                    log("No IDR frame after $framesDroppedWaiting frames; decoding anyway")
                }
            }
            try {
                val index = active.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (index < 0) {
                    if (++inputDrops % INPUT_DROP_LOG_INTERVAL == 1) log("Decoder is behind; dropped $inputDrops frames")
                    return
                }
                val buffer = active.getInputBuffer(index) ?: return
                buffer.clear()
                if (length > buffer.capacity()) {
                    active.queueInputBuffer(index, 0, 0, timestampMicros, 0)
                    log("Frame of $length bytes does not fit the decoder input; dropped")
                    return
                }
                buffer.put(data, offset, length)
                active.queueInputBuffer(index, 0, length, timestampMicros, 0)
            } catch (error: RuntimeException) {
                log("Video decoder failed (${error.javaClass.simpleName}); restarting")
                stopLocked()
                startLocked()
            }
        }
    }

    override fun onStop() {
        synchronized(lock) { stopLocked() }
    }

    /** Releases the decoder and forgets the surface. The decoder is not reused afterwards. */
    fun release() {
        synchronized(renderLock) { rendering = false }
        synchronized(lock) {
            stopLocked()
            surface = null
            config = null
        }
    }

    private fun startLocked() {
        val currentSurface = surface ?: return
        val currentConfig = config ?: return
        val sets = H264.parameterSets(currentConfig)
        var created: MediaCodec? = null
        try {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                sets.sps?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
                sets.pps?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            val decoder = MediaCodec.createDecoderByType(MIME)
            created = decoder
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                decoder.codecInfo.getCapabilitiesForType(MIME).isFeatureSupported(FEATURE_LOW_LATENCY)
            ) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            decoder.configure(format, currentSurface, null, 0)
            decoder.start()
            codec = decoder
            waitingForKeyFrame = true
            framesDroppedWaiting = 0
            val id = ++generation
            synchronized(renderLock) { rendering = true }
            outputThread = Thread({ drain(decoder, id) }, "aap-video-output").apply {
                isDaemon = true
                start()
            }
            log("Video decoder ${decoder.name} started ${width}x$height")
        } catch (error: Exception) {
            log("Video decoder could not start: ${error.javaClass.simpleName} ${error.message.orEmpty()}")
            runCatching { created?.release() }
            codec = null
        }
    }

    private fun stopLocked() {
        val active = codec ?: return
        codec = null
        generation++
        synchronized(renderLock) { rendering = false }
        runCatching { active.stop() }
        runCatching { active.release() }
        val thread = outputThread
        outputThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun drain(decoder: MediaCodec, id: Int) {
        val info = MediaCodec.BufferInfo()
        while (id == generation) {
            try {
                val index = decoder.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                if (index >= 0) {
                    synchronized(renderLock) { decoder.releaseOutputBuffer(index, rendering) }
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    log("Video output format ${decoder.outputFormat}")
                }
            } catch (_: RuntimeException) {
                // The decoder was stopped or released underneath this thread.
                return
            }
        }
    }

    private companion object {
        const val MIME = "video/avc"
        const val MAX_INPUT_SIZE = 1 shl 20
        const val INPUT_TIMEOUT_US = 20_000L
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val JOIN_MILLIS = 500L
        const val MAX_FRAMES_WAITING_FOR_KEY = 45
        const val INPUT_DROP_LOG_INTERVAL = 100

        const val FEATURE_LOW_LATENCY = "low-latency"
    }
}
