package com.burak.spotiwidget

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.MediaMetadata
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * Places a transparent touch-only overlay exactly over the widget's seek strip while the home
 * screen is visible, so the strip can be drag-seeked (AppWidgets can't receive drag gestures).
 */
class SeekOverlayService : AccessibilityService() {

    private var homePkg: String? = null
    private lateinit var windowManager: WindowManager
    private lateinit var dragView: SeekDragView
    private lateinit var params: WindowManager.LayoutParams
    private var added = false
    private var lastRect: Rect? = null

    private val handler = Handler(Looper.getMainLooper())
    private var lastContentRelocate = 0L
    private val relocateRunnable = Runnable { relocate() }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) hide()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        homePkg = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName

        windowManager = getSystemService(WindowManager::class.java)
        dragView = SeekDragView(this)
        params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        dragView.visibility = View.GONE
        windowManager.addView(dragView, params)
        added = true

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (dragView.isDragging) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (event.packageName?.toString() == homePkg) {
                    hide()
                    scheduleRelocate(220)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                scheduleRelocate(60)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (dragView.visibility != View.VISIBLE) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastContentRelocate >= 500) {
                        lastContentRelocate = now
                        scheduleRelocate(0)
                    }
                }
            }
        }
    }

    private fun scheduleRelocate(delayMs: Long) {
        handler.removeCallbacks(relocateRunnable)
        handler.postDelayed(relocateRunnable, delayMs)
    }

    private fun relocate() {
        if (dragView.isDragging) return
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != homePkg) {
            hide()
            return
        }
        val node = root.findAccessibilityNodeInfosByViewId("com.burak.spotiwidget:id/seekStrip")
            ?.firstOrNull { it.isVisibleToUser }
            ?: root.findAccessibilityNodeInfosByText("spotiwidget_seek")?.firstOrNull { it.isVisibleToUser }
        if (node == null) {
            hide()
            return
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) {
            hide()
            return
        }
        val duration = SpotifyListenerService.controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration <= 0) {
            hide()
            return
        }
        if (rect != lastRect) {
            lastRect = Rect(rect)
            params.x = rect.left
            params.y = rect.top
            params.width = rect.width()
            params.height = rect.height()
            windowManager.updateViewLayout(dragView, params)
        }
        dragView.visibility = View.VISIBLE
    }

    private fun hide() {
        if (dragView.visibility != View.GONE) dragView.visibility = View.GONE
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        removeOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    private fun removeOverlay() {
        handler.removeCallbacks(relocateRunnable)
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        if (added) {
            try { windowManager.removeView(dragView) } catch (_: Exception) {}
            added = false
        }
    }
}

/** Transparent when idle; draws the drag track only while an active drag is in progress. */
private class SeekDragView(context: Context) : View(context) {

    var isDragging = false
        private set
    private var fraction = 0f
    private var downX = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2A2A.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1DB954.toInt() }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private fun dp(v: Float) = v * density

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging && abs(event.x - downX) > touchSlop) isDragging = true
                if (isDragging) {
                    fraction = (event.x / width.toFloat()).coerceIn(0f, 1f)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    val c = SpotifyListenerService.controller
                    val duration = c?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                    if (c != null && duration > 0) {
                        c.transportControls.seekTo((fraction * duration).toLong())
                        WidgetRenderer.setProgressFraction(context, fraction.toDouble())
                    }
                }
                isDragging = false
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (!isDragging) return
        val trackPx = dp(3f)
        val top = height - trackPx
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), trackPaint)
        canvas.drawRect(0f, top, width * fraction, height.toFloat(), fillPaint)

        val radius = dp(8f)
        val cx = (width * fraction).coerceIn(radius, width - radius)
        val cy = height - dp(1.5f)
        canvas.drawCircle(cx, cy, radius, dotPaint)
    }
}
