package com.frameworkstudios.autoswipeswiper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Floating control panel with two draggable points (A and B). While running,
 * repeatedly swipes between them — direction and timing are all live-adjustable
 * from the panel, even while the loop is running.
 */
class SwipeOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var panelView: View
    private lateinit var panelParams: WindowManager.LayoutParams

    private var pointA: CrosshairView? = null
    private var pointB: CrosshairView? = null

    /** false = swipe A→B, true = swipe B→A. Read fresh every loop tick, so
     *  toggling it mid-run takes effect on the very next swipe. */
    private var reversed = false

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var loopJob: Job? = null

    private lateinit var statusText: TextView
    private lateinit var directionText: TextView
    private lateinit var intervalInput: EditText
    private lateinit var chkRandomize: CheckBox
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnClose: Button
    private lateinit var btnDirection: Button

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
        directionText = panelView.findViewById(R.id.directionText)
        intervalInput = panelView.findViewById(R.id.intervalInput)
        chkRandomize = panelView.findViewById(R.id.chkRandomize)
        btnStart = panelView.findViewById(R.id.btnStart)
        btnStop = panelView.findViewById(R.id.btnStop)
        btnClose = panelView.findViewById(R.id.btnClose)
        btnDirection = panelView.findViewById(R.id.btnDirection)

        makeEditable(intervalInput)

        btnStart.setOnClickListener { startLoop() }
        btnStop.setOnClickListener { stopLoop("Stopped.") }
        btnClose.setOnClickListener { stopSelf() }
        btnDirection.setOnClickListener { toggleDirection() }

        listOf(R.id.btnPreset05, R.id.btnPreset1, R.id.btnPreset2, R.id.btnPreset3).forEach { id ->
            val btn = panelView.findViewById<Button>(id)
            btn.setOnClickListener { intervalInput.setText(btn.tag as String) }
        }

        btnStop.isEnabled = false
    }

    private fun addPoints() {
        val (w, h) = screenSize()
        pointA = addCrosshair(getColor(R.color.point_a), "A", w / 3, h / 2)
        pointB = addCrosshair(getColor(R.color.point_b), "B", w * 2 / 3, h / 2)
    }

    private fun toggleDirection() {
        reversed = !reversed
        directionText.text = if (reversed) "Direction: B → A" else "Direction: A → B"
        vibrate(20)
    }

    // ---- swipe loop ---------------------------------------------------------

    private fun startLoop() {
        val a = pointA ?: return
        val b = pointB ?: return

        // Immediate feedback that the tap registered, before we even know if
        // the accessibility service is connected.
        vibrate(30)

        if (SwipeAccessibilityService.instance == null) {
            // Fail loudly and immediately instead of silently retrying for a
            // few seconds — this is almost always an Accessibility permission
            // that looks "on" in Settings but isn't actually bound (common on
            // MIUI/HyperOS). Long buzz = distinct from the short "tap" buzz.
            vibrate(400)
            toast("Accessibility not connected — reopen Accessibility settings and toggle it off/on")
            setStatus("Accessibility not connected.", error = true)
            return
        }

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        setStatus("Running…", running = true)

        loopJob = serviceScope.launch {
            var missing = 0
            while (isActive) {
                val service = SwipeAccessibilityService.instance
                if (service == null) {
                    missing++
                    if (missing >= 6) {
                        vibrate(400)
                        setStatus("Accessibility lost. Re-enable then Start again.", error = true)
                        break
                    }
                    setStatus("Service reconnecting…", error = true)
                    delay(500); continue
                }
                missing = 0

                val from = if (reversed) b else a
                val to = if (reversed) a else b
                val (fx, fy) = crosshairCenter(from)
                val (tx, ty) = crosshairCenter(to)
                try {
                    service.performSwipe(fx, fy, tx, ty)
                    vibrate(15)
                    setStatus("Running…", running = true)
                } catch (e: Exception) {
                    setStatus("Gesture error, retrying…", error = true)
                }
                delay(currentDelayMs())
            }
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }
    }

    private fun stopLoop(message: String) {
        loopJob?.cancel(); loopJob = null
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        setStatus(message)
    }

    /** Delay before the next swipe: a random 1-3s if the checkbox is on,
     *  otherwise whatever's typed in the interval field (seconds, converted to ms). */
    private fun currentDelayMs(): Long {
        if (chkRandomize.isChecked) {
            return Random.nextLong(1000L, 3001L)
        }
        val seconds = intervalInput.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 1.0
        return (seconds * 1000).toLong()
    }

    private fun setStatus(message: String, running: Boolean = false, error: Boolean = false) {
        statusText.text = message
        val colorRes = when {
            error -> R.color.danger
            running -> R.color.success
            else -> R.color.text_dim
        }
        statusText.setTextColor(getColor(colorRes))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun vibrate(ms: Long) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") v.vibrate(ms)
        }
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
        val (screenW, screenH) = screenSize()
        // Android reserves a strip along the left/right edges (varies by
        // phone, ~24-48dp) exclusively for the system back-gesture. A swipe
        // that starts inside that strip gets intercepted as "back" before it
        // ever reaches the target app — so keep points out of it entirely.
        val margin = (64 * resources.displayMetrics.density).toInt()
        val minX = margin
        val maxX = (screenW - size - margin).coerceAtLeast(minX)
        val minY = margin
        val maxY = (screenH - size - margin).coerceAtLeast(minY)

        val ch = CrosshairView(this, color, label)
        val params = WindowManager.LayoutParams(
            size, size, overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (cx - size / 2).coerceIn(minX, maxX)
            y = (cy - size / 2).coerceIn(minY, maxY)
        }
        ch.onMoved = { rx, ry ->
            params.x = (rx - size / 2f).toInt().coerceIn(minX, maxX)
            params.y = (ry - size / 2f).toInt().coerceIn(minY, maxY)
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
