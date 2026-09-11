package com.frameworkstudios.autoswipeswiper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Floating control panel with two draggable points (A and B). While running,
 * repeatedly swipes from A to B, waiting [loopIntervalMs] between each swipe.
 */
class SwipeOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var panelView: View
    private lateinit var panelParams: WindowManager.LayoutParams

    private var pointA: CrosshairView? = null
    private var pointB: CrosshairView? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var loopJob: Job? = null

    private lateinit var statusText: TextView
    private lateinit var intervalInput: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnClose: Button

    private val overlayWindowType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundWithNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
        addPoints()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        loopJob?.cancel()
        removePoints()
        if (::panelView.isInitialized) {
            runCatching { windowManager.removeView(panelView) }
        }
    }

    private fun startForegroundWithNotification() {
        val channelId = "autoswipeswiper_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "AutoSwipeSwiper", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AutoSwipeSwiper running")
            .setContentText("Floating controls are active.")
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
    }

    // ---- panel setup -------------------------------------------------------

    private fun showPanel() {
        panelView = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40; y = 200
        }

        windowManager.addView(panelView, panelParams)
        setupDrag()

        statusText = panelView.findViewById(R.id.statusText)
        intervalInput = panelView.findViewById(R.id.intervalInput)
        btnStart = panelView.findViewById(R.id.btnStart)
        btnStop = panelView.findViewById(R.id.btnStop)
        btnClose = panelView.findViewById(R.id.btnClose)

        makeEditable(intervalInput)

        btnStart.setOnClickListener { startLoop() }
        btnStop.setOnClickListener { stopLoop("Stopped.") }
        btnClose.setOnClickListener { stopSelf() }

        btnStop.isEnabled = false
    }

    private fun addPoints() {
        val (w, h) = screenSize()
        pointA = addCrosshair(getColor(R.color.point_a), "A", w / 3, h / 2)
        pointB = addCrosshair(getColor(R.color.point_b), "B", w * 2 / 3, h / 2)
    }

    // ---- swipe loop ---------------------------------------------------------

    private fun startLoop() {
        val a = pointA ?: return
        val b = pointB ?: return
        val intervalMs = intervalInput.text.toString().toLongOrNull()?.coerceAtLeast(0) ?: 1000L

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        statusText.text = "Running…"

        loopJob = serviceScope.launch {
            var missing = 0
            while (isActive) {
                val service = SwipeAccessibilityService.instance
                if (service == null) {
                    missing++
                    if (missing >= 6) {
                        statusText.text = "Accessibility lost. Re-enable then Start again."
                        break
                    }
                    statusText.text = "Service reconnecting…"
                    delay(500); continue
                }
                missing = 0
                val (ax, ay) = crosshairCenter(a)
                val (bx, by) = crosshairCenter(b)
                try {
                    service.performSwipe(ax, ay, bx, by)
                    statusText.text = "Running…"
                } catch (e: Exception) {
                    statusText.text = "Gesture error, retrying…"
                }
                delay(intervalMs)
            }
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }
    }

    private fun stopLoop(message: String) {
        loopJob?.cancel(); loopJob = null
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        statusText.text = message
    }

    // ---- helpers -------------------------------------------------------------

    private fun crosshairCenter(view: CrosshairView): Pair<Float, Float> {
        val p = view.layoutParams as WindowManager.LayoutParams
        return Pair(p.x + view.width / 2f, p.y + view.height / 2f)
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun addCrosshair(color: Int, label: String, cx: Int, cy: Int): CrosshairView {
        val size = 140
        val ch = CrosshairView(this, color, label)
        val params = WindowManager.LayoutParams(
            size, size, overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cx - size / 2; y = cy - size / 2
        }
        ch.onMoved = { rx, ry ->
            params.x = (rx - size / 2f).toInt()
            params.y = (ry - size / 2f).toInt()
            windowManager.updateViewLayout(ch, params)
        }
        windowManager.addView(ch, params)
        return ch
    }

    private fun removePoints() {
        listOf(pointA, pointB).forEach { it?.let { v -> runCatching { windowManager.removeView(v) } } }
        pointA = null; pointB = null
    }

    private fun setupDrag() {
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        val listener = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = panelParams.x; initialY = panelParams.y
                    touchX = e.rawX; touchY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = initialX + (e.rawX - touchX).toInt()
                    panelParams.y = initialY + (e.rawY - touchY).toInt()
                    windowManager.updateViewLayout(panelView, panelParams); true
                }
                else -> false
            }
        }
        panelView.findViewById<LinearLayout>(R.id.dragHandle).setOnTouchListener(listener)
    }

    private fun makeEditable(editText: EditText) {
        editText.setOnClickListener {
            setPanelFocusable(true)
            editText.post {
                editText.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(editText.windowToken, 0)
                setPanelFocusable(false)
            }
        }
    }

    private fun setPanelFocusable(focusable: Boolean) {
        if (!::panelParams.isInitialized) return
        panelParams.flags = if (focusable) {
            panelParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            panelParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { windowManager.updateViewLayout(panelView, panelParams) }
    }

    companion object {
        var instance: SwipeOverlayService? = null
            private set
    }
}
