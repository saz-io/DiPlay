package com.shilapi.xcertplay.androidauto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays one Android Auto PCM stream. Audio is real-time, so a backlog is dropped instead of
 * played late: when the queue is full the oldest chunk is discarded.
 */
class AapAudioPlayer(
    context: Context,
    private val purpose: Purpose,
    private val log: (String) -> Unit = {},
) : AapAudioSink {
    enum class Purpose { MEDIA, GUIDANCE, SYSTEM }

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CHUNKS)
    private val lock = Any()

    private var track: AudioTrack? = null
    private var writer: Thread? = null
    private var focus: AudioFocusRequestCompat? = null
    private var dropped = 0

    @Volatile private var running = false

    override fun start(format: AapAudioFormat) {
        synchronized(lock) {
            stopLocked()
            val channelMask = if (format.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minimum = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
            val bytesPerSecond = format.sampleRate * format.channels * 2
            val bufferBytes = maxOf(minimum * 2, bytesPerSecond / 5)
            val builder = AudioTrack.Builder()
                .setAudioAttributes(attributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(format.sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(encoding)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val created = try {
                builder.build()
            } catch (error: Exception) {
                log("$purpose audio could not start: ${error.javaClass.simpleName}")
                return
            }
            requestFocus()
            created.play()
            track = created
            queue.clear()
            running = true
            writer = Thread({ writeLoop(created) }, "aap-audio-$purpose").apply {
                isDaemon = true
                start()
            }
            log("$purpose audio started ${format.sampleRate} Hz ${format.channels} ch")
        }
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        if (!running || length <= 0) return
        val chunk = data.copyOfRange(offset, offset + length)
        if (!queue.offer(chunk)) {
            queue.poll()
            queue.offer(chunk)
            if (++dropped % DROP_LOG_INTERVAL == 1) log("$purpose audio is behind; dropped $dropped chunks")
        }
    }

    override fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        running = false
        val thread = writer
        writer = null
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        track = null
        queue.clear()
        abandonFocus()
    }

    private fun writeLoop(output: AudioTrack) {
        try {
            while (running) {
                val chunk = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
                if (output.write(chunk, 0, chunk.size) < 0) {
                    log("$purpose audio write failed")
                    return
                }
            }
        } catch (_: InterruptedException) {
            // stop() interrupts the writer.
        } catch (_: RuntimeException) {
            // The track was released by stop().
        }
    }

    private fun attributes(): AudioAttributes {
        val (usage, content) = when (purpose) {
            Purpose.MEDIA -> AudioAttributes.USAGE_MEDIA to AudioAttributes.CONTENT_TYPE_MUSIC
            Purpose.GUIDANCE -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE to AudioAttributes.CONTENT_TYPE_SPEECH
            Purpose.SYSTEM -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION to AudioAttributes.CONTENT_TYPE_SONIFICATION
        }
        return AudioAttributes.Builder().setUsage(usage).setContentType(content).build()
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        val gain = when (purpose) {
            Purpose.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            Purpose.GUIDANCE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            Purpose.SYSTEM -> return
        }
        val request = AudioFocusRequestCompat(gain, attributes(), { }, Handler(Looper.getMainLooper()))
        focus = request
        request.request(manager)
    }

    private fun abandonFocus() {
        val manager = audioManager
        val request = focus
        focus = null
        if (manager != null && request != null) request.abandon(manager)
    }

    private companion object {
        const val QUEUE_CHUNKS = 48
        const val POLL_MILLIS = 100L
        const val JOIN_MILLIS = 500L
        const val DROP_LOG_INTERVAL = 50
    }
}
