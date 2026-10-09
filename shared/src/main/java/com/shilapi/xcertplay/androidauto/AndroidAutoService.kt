package com.shilapi.xcertplay.androidauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the Android Auto receiver alive: it listens on Bluetooth for a phone and runs one
 * projection at a time. The app starts it through [AndroidAutoLauncher].
 */
class AndroidAutoService : Service() {
    private val lock = Any()
    private var acceptor: AapBluetoothAcceptor? = null
    private var connection: AndroidAutoConnection? = null
    private var config: AndroidAutoRuntimeConfig? = null
    private var removeStateListener: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // A start through startForegroundService() must always reach startForeground().
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT -> {
                AndroidAutoRuntime.active?.session?.disconnect()
                return START_NOT_STICKY
            }
        }
        val requested = intent?.let(AndroidAutoRuntimeConfig::readFrom)
        if (requested == null) {
            startForegroundCompat(placeholderNotification())
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        synchronized(lock) {
            config = requested
            startForegroundCompat(buildNotification(requested, AndroidAutoState.snapshot))
            if (removeStateListener == null) {
                removeStateListener = AndroidAutoState.addListener { snapshot ->
                    config?.let { notificationManager()?.notify(NOTIFICATION_ID, buildNotification(it, snapshot)) }
                }
            }
            if (acceptor == null) startListening()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun startListening() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            AndroidAutoState.update(AndroidAutoPhase.ERROR, failure = AndroidAutoFailure.BLUETOOTH_OFF, detail = "No Bluetooth adapter")
            return
        }
        val listening = AapBluetoothAcceptor(
            adapter = adapter,
            onConnection = ::serve,
            onProblem = { failure, detail ->
                Log.w(AndroidAutoConnection.TAG, "Bluetooth: $detail")
                AndroidAutoState.update(AndroidAutoPhase.ERROR, failure = failure, detail = detail)
            },
            log = { Log.i(AndroidAutoConnection.TAG, it) },
        )
        acceptor = listening
        AndroidAutoState.update(AndroidAutoPhase.LISTENING)
        listening.start()
    }

    /** Runs on the acceptor thread for the whole life of one projection. */
    private fun serve(socket: android.bluetooth.BluetoothSocket) {
        val settings = synchronized(lock) { config } ?: return
        val current = AndroidAutoConnection(applicationContext, settings, AndroidAutoIdentityStore(this))
        synchronized(lock) { connection = current }
        try {
            current.run(socket)
        } finally {
            synchronized(lock) { if (connection === current) connection = null }
        }
        // A finished or failed attempt leaves the receiver listening again for the next phone.
        if (AndroidAutoState.snapshot.phase != AndroidAutoPhase.ERROR) AndroidAutoState.update(AndroidAutoPhase.LISTENING)
    }

    private fun shutdown() {
        val running: AapBluetoothAcceptor?
        val active: AndroidAutoConnection?
        synchronized(lock) {
            running = acceptor
            active = connection
            acceptor = null
            connection = null
            config = null
            removeStateListener?.invoke()
            removeStateListener = null
        }
        running?.close()
        active?.cancel()
        AndroidAutoState.update(AndroidAutoPhase.OFF)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notificationManager(): NotificationManager? = getSystemService(NotificationManager::class.java)

    private fun builder(): Notification.Builder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager()?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Android Auto", NotificationManager.IMPORTANCE_LOW),
            )
            return Notification.Builder(this, CHANNEL)
        }
        // Android 7.x has no notification channels; the priority stands in for the importance.
        @Suppress("DEPRECATION")
        return Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
    }

    private fun placeholderNotification(): Notification =
        builder().setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("DiPlay").setOngoing(true).build()

    private fun buildNotification(settings: AndroidAutoRuntimeConfig, snapshot: AndroidAutoSnapshot): Notification {
        val projecting = snapshot.phase == AndroidAutoPhase.PROJECTING || snapshot.phase == AndroidAutoPhase.CONNECTING
        val text = if (projecting) {
            snapshot.phoneName?.let { "${settings.texts.connected} · $it" } ?: settings.texts.connected
        } else {
            settings.texts.waiting
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val projection = Intent(this, AndroidAutoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val builder = builder()
            .setSmallIcon(if (settings.notificationIcon != 0) settings.notificationIcon else android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(settings.texts.title)
            .setContentText(text)
            .setOngoing(true)
        if (projecting) {
            builder.setContentIntent(
                PendingIntent.getActivity(this, 1, projection, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            val disconnect = PendingIntent.getService(
                this, 2, Intent(this, AndroidAutoService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, settings.texts.disconnect, disconnect).build())
        } else if (open != null) {
            builder.setContentIntent(open)
        }
        return builder.build()
    }

    companion object {
        const val ACTION_STOP = "com.shihab.diplay.androidauto.STOP"
        const val ACTION_DISCONNECT = "com.shihab.diplay.androidauto.DISCONNECT"
        private const val CHANNEL = "diplay_android_auto"
        private const val NOTIFICATION_ID = 2
    }
}

/** Starts and stops the receiver from the app. */
object AndroidAutoLauncher {
    fun start(context: Context, config: AndroidAutoRuntimeConfig) {
        val intent = config.writeTo(Intent(context, AndroidAutoService::class.java))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        try {
            context.startService(Intent(context, AndroidAutoService::class.java).setAction(AndroidAutoService.ACTION_STOP))
        } catch (_: IllegalStateException) {
            // Background start limits: the app is not in the foreground, and the service ends with its process.
        }
    }

    /** Runtime permissions the receiver needs but the app has not been granted. */
    fun missingPermissions(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return listOf(android.Manifest.permission.BLUETOOTH_CONNECT).filter {
            context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}
