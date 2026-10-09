package com.shilapi.xcertplay.androidauto

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * The projection screen: a full-screen surface that shows the phone's picture and sends touches
 * and media keys back. The decoder and the session belong to [AndroidAutoService]; this screen
 * only shows them, so leaving it does not end the connection.
 */
class AndroidAutoActivity : Activity(), SurfaceHolder.Callback {
    private var container: FrameLayout? = null
    private var surfaceView: SurfaceView? = null
    private var translator: AapTouchTranslator? = null
    private var removeStateListener: (() -> Unit)? = null
    private var removeLeaveListener: (() -> Unit)? = null
    private val forwardedKeys = AapHeadUnitConfig.DEFAULT_KEYCODES.toSet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val active = AndroidAutoRuntime.active
        if (active == null) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val mapper = AapTouchTranslator(active.video)
        translator = mapper

        // The picture must never mirror in right-to-left languages: touches use absolute positions.
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = true
        }
        val video = SurfaceView(this).apply { holder.addCallback(this@AndroidAutoActivity) }
        root.addView(video, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ -> place(right - left, bottom - top) }
        root.setOnTouchListener { _, event -> sendTouch(event); true }
        container = root
        surfaceView = video
        setContentView(root)

        removeStateListener = AndroidAutoState.addListener { snapshot ->
            if (snapshot.phase != AndroidAutoPhase.CONNECTING && snapshot.phase != AndroidAutoPhase.PROJECTING) {
                runOnUiThread { if (!isFinishing) finish() }
            }
        }
        removeLeaveListener = AndroidAutoRuntime.addLeaveListener { runOnUiThread { if (!isFinishing) finish() } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        removeStateListener?.invoke()
        removeLeaveListener?.invoke()
        removeStateListener = null
        removeLeaveListener = null
        super.onDestroy()
    }

    // ---- surface ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        AndroidAutoRuntime.active?.decoder?.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val active = AndroidAutoRuntime.active ?: return
        active.decoder.setSurface(holder.surface)
        // Only now can the phone's picture be shown: tell it to start projecting.
        active.session.setProjectionVisible(true)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val active = AndroidAutoRuntime.active ?: return
        active.decoder.setSurface(null)
        active.session.setProjectionVisible(false)
    }

    private fun place(containerWidth: Int, containerHeight: Int) {
        val mapper = translator ?: return
        val video = surfaceView ?: return
        mapper.layout(containerWidth, containerHeight)
        val params = FrameLayout.LayoutParams(mapper.streamWidth, mapper.streamHeight, Gravity.TOP or Gravity.START).apply {
            leftMargin = mapper.streamLeft
            topMargin = mapper.streamTop
        }
        if (video.layoutParams.width != params.width || video.layoutParams.height != params.height ||
            (video.layoutParams as? FrameLayout.LayoutParams)?.let { it.leftMargin != params.leftMargin || it.topMargin != params.topMargin } != false
        ) {
            video.layoutParams = params
        }
    }

    // ---- input ----

    private fun sendTouch(event: MotionEvent) {
        val active = AndroidAutoRuntime.active ?: return
        val mapper = translator ?: return
        val pointerAction = event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_UP
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> AapPointerAction.DOWN
            MotionEvent.ACTION_POINTER_DOWN -> AapPointerAction.POINTER_DOWN
            MotionEvent.ACTION_MOVE -> AapPointerAction.MOVED
            MotionEvent.ACTION_POINTER_UP -> AapPointerAction.POINTER_UP
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> AapPointerAction.UP
            else -> return
        }
        val starting = action == AapPointerAction.DOWN || action == AapPointerAction.POINTER_DOWN
        val changed = if (pointerAction) event.actionIndex else 0
        val pointers = ArrayList<AapTouchPointer>(event.pointerCount)
        var actionIndex = 0
        for (index in 0 until event.pointerCount) {
            val x = event.getX(index)
            val y = event.getY(index)
            // A touch may start only on the picture; one that is under way follows the finger to the edge.
            val mapped = if (starting && index == changed) mapper.translate(x, y) ?: return else mapper.translateClamped(x, y)
            if (index == changed) actionIndex = pointers.size
            pointers += AapTouchPointer(event.getPointerId(index), mapped.first, mapped.second)
        }
        active.session.sendTouch(action, pointers, actionIndex)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val active = AndroidAutoRuntime.active
        if (active != null && event.keyCode in forwardedKeys) {
            // Auto-repeat would send a key the phone never saw pressed.
            if (event.repeatCount == 0) active.session.sendKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
