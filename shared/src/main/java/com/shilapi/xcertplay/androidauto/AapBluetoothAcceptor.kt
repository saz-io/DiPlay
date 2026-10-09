package com.shilapi.xcertplay.androidauto

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.IOException

/**
 * Listens for the phone on the Android Auto Bluetooth service.
 *
 * A paired phone with wireless Android Auto looks for [AapWireless.SERVICE_UUID] on the car and
 * opens it. [onConnection] runs on this thread and returns when that projection has ended, so one
 * phone is served at a time; listening resumes afterwards.
 */
class AapBluetoothAcceptor(
    private val adapter: BluetoothAdapter,
    private val onConnection: (BluetoothSocket) -> Unit,
    private val onProblem: (AndroidAutoFailure, String) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private var thread: Thread? = null
    private var server: BluetoothServerSocket? = null

    @Volatile private var closed = false

    fun start() {
        synchronized(lock) {
            if (thread != null || closed) return
            thread = Thread(::loop, "aap-bluetooth-accept").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun close() {
        closed = true
        synchronized(lock) {
            try {
                server?.close()
            } catch (_: IOException) {
                // Closing the listener is what unblocks accept().
            }
            thread?.interrupt()
        }
    }

    private fun loop() {
        var reported: AndroidAutoFailure? = null
        while (!closed) {
            if (!adapter.isEnabled) {
                reported = report(reported, AndroidAutoFailure.BLUETOOTH_OFF, "Bluetooth is off")
                if (!pause()) return
                continue
            }
            val listener = try {
                adapter.listenUsingRfcommWithServiceRecord(AapWireless.SERVICE_NAME, AapWireless.SERVICE_UUID)
            } catch (_: SecurityException) {
                reported = report(reported, AndroidAutoFailure.BLUETOOTH_PERMISSION, "Bluetooth permission is missing")
                if (!pause()) return
                continue
            } catch (error: IOException) {
                reported = report(reported, AndroidAutoFailure.UNKNOWN, "Bluetooth could not listen: ${error.message.orEmpty()}")
                if (!pause()) return
                continue
            }
            reported = null
            synchronized(lock) { server = listener }
            if (closed) {
                closeQuietly(listener)
                return
            }
            try {
                val socket = listener.accept()
                // One phone at a time: stop advertising while this one is served.
                closeQuietly(listener)
                synchronized(lock) { server = null }
                log("Phone connected over Bluetooth")
                try {
                    onConnection(socket)
                } finally {
                    try {
                        socket.close()
                    } catch (_: IOException) {
                        // Already closed by the connection.
                    }
                }
            } catch (error: IOException) {
                if (!closed) log("Bluetooth accept ended: ${error.message.orEmpty()}")
            } catch (error: SecurityException) {
                reported = report(reported, AndroidAutoFailure.BLUETOOTH_PERMISSION, "Bluetooth permission is missing")
            } finally {
                closeQuietly(listener)
                synchronized(lock) { server = null }
            }
            if (!closed && !pause()) return
        }
    }

    private fun report(previous: AndroidAutoFailure?, failure: AndroidAutoFailure, detail: String): AndroidAutoFailure {
        if (previous != failure) onProblem(failure, detail)
        return failure
    }

    /** Waits before retrying; false when the acceptor was closed meanwhile. */
    private fun pause(): Boolean {
        try {
            Thread.sleep(RETRY_MILLIS)
        } catch (_: InterruptedException) {
            return !closed
        }
        return !closed
    }

    private fun closeQuietly(listener: BluetoothServerSocket) {
        try {
            listener.close()
        } catch (_: IOException) {
            // Nothing left to release.
        }
    }

    private companion object {
        const val RETRY_MILLIS = 3_000L
    }
}
