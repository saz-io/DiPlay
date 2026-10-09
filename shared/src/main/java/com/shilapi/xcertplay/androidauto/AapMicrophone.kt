package com.shilapi.xcertplay.androidauto

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Captures the head unit's microphone as 16-bit mono PCM for the phone's voice assistant and calls.
 * The caller must hold the RECORD_AUDIO permission; without it capture fails and is logged.
 */
class AapMicrophone(
    private val format: AapAudioFormat,
    private val log: (String) -> Unit = {},
) : AapMicrophoneSource {
    private val lock = Any()
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    override fun open(onData: (ByteArray, Int) -> Unit) {
        synchronized(lock) {
            if (running) return
            val minimum = AudioRecord.getMinBufferSize(format.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val chunk = format.sampleRate * 2 * CHUNK_MILLIS / 1000
            val created = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    format.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum * 2, chunk * 4),
                )
            } catch (error: Exception) {
                log("Microphone could not open: ${error.javaClass.simpleName}")
                return
            }
            if (created.state != AudioRecord.STATE_INITIALIZED) {
                log("Microphone is unavailable; check the microphone permission")
                created.release()
                return
            }
            try {
                created.startRecording()
            } catch (error: IllegalStateException) {
                log("Microphone could not start")
                created.release()
                return
            }
            record = created
            running = true
            thread = Thread({ readLoop(created, chunk, onData) }, "aap-microphone").apply {
                isDaemon = true
                start()
            }
        }
    }

    override fun close() {
        val joinThread: Thread?
        synchronized(lock) {
            running = false
            joinThread = thread
            thread = null
            record?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            record = null
        }
        if (joinThread != null && joinThread !== Thread.currentThread()) {
            try {
                joinThread.join(JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun readLoop(source: AudioRecord, chunk: Int, onData: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(chunk)
        try {
            while (running) {
                val count = source.read(buffer, 0, buffer.size)
                if (count > 0) onData(buffer, count) else if (count < 0) return
            }
        } catch (_: RuntimeException) {
            // The recorder was released by close().
        }
    }

    private companion object {
        const val CHUNK_MILLIS = 40
        const val JOIN_MILLIS = 500L
    }
}
