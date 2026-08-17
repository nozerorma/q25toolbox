package com.kgr.q25toolbox.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.res.Resources
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.TickerController
import com.kgr.q25toolbox.modules.TickerSettings
import kotlin.math.ceil

/**
 * Renders the ticker as a single TYPE_ACCESSIBILITY_OVERLAY window pinned to the top of
 * the screen. There's only ever one instance: a notification arriving while a ticker is
 * already showing replaces its content and restarts the scroll rather than stacking a
 * second window, matching how a real status bar ticker behaves ("latest wins").
 *
 * TYPE_ACCESSIBILITY_OVERLAY (not TYPE_APPLICATION_OVERLAY/SYSTEM_ALERT_WINDOW) is what
 * actually lets this render above the real status bar - confirmed by decompiling Super
 * Status Bar (com.tombayley.statusbar), which uses this exact window type + flag
 * combination (FLAG_LAYOUT_INSET_DECOR | FLAG_LAYOUT_NO_LIMITS | FLAG_LAYOUT_IN_SCREEN)
 * for its own status-bar-replacement window. Unlike TYPE_APPLICATION_OVERLAY, this type
 * requires no "draw over other apps" permission at all, but it can only be added via a
 * WindowManager obtained from a *running AccessibilityService's* own Context - see
 * [Q25AccessibilityService.instance].
 *
 * Scroll speed/start-delay are directly configurable (see [TickerSettings]), which is
 * why this drives its own ObjectAnimator on `translationX` instead of TextView's built-in
 * marquee - the stock marquee has no public API for either.
 */
object TickerOverlayController {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var animator: ObjectAnimator? = null
    private var iconAnimator: ObjectAnimator? = null
    private var dismissRunnable: Runnable? = null

    /** How long the icon takes to fade out once the text starts scrolling. */
    private const val ICON_FADE_DURATION_MS = 250L

    /** How far down the user has to drag on the ticker before it opens the shade. */
    private const val SWIPE_OPEN_THRESHOLD_DP = 16f

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(service: AccessibilityService, view: View, contentIntent: PendingIntent?) {
        val thresholdPx = SWIPE_OPEN_THRESHOLD_DP * service.resources.displayMetrics.density
        // While the ticker is up it covers the status bar, so a swipe down that starts on it
        // would otherwise land on whatever app is behind instead of pulling the shade - the
        // one gesture you'd expect to still work on something occupying the status bar. The
        // ticker forwards it to the shade itself (via the accessibility global action, which
        // needs no root and lands immediately) and gets out of the way.
        var handled = false
        val detector = GestureDetector(service, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                handled = false
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                val start = e1 ?: return false
                if (!handled && e2.rawY - start.rawY > thresholdPx) {
                    handled = true
                    openNotificationShade(service)
                }
                return handled
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (!handled && velocityY > 0) {
                    handled = true
                    openNotificationShade(service)
                }
                return handled
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val intent = contentIntent ?: return false
                handled = true
                try {
                    intent.send()
                } catch (_: PendingIntent.CanceledException) {
                    // Notification/app gone by the time the user tapped it - ignore.
                }
                hideInternal()
                return true
            }
        })
        view.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun openNotificationShade(service: AccessibilityService) {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        hideInternal()
    }

    /**
     * Whether a ticker could actually be displayed right now: the screen is on, and the
     * accessibility service is running (its Context is the only one that can add a
     * TYPE_ACCESSIBILITY_OVERLAY window). Checked by the caller *before* it suppresses any
     * heads-up popup, so a notification is never left with neither a popup nor a ticker.
     */
    fun canShow(context: Context): Boolean {
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive && Q25AccessibilityService.instance != null
    }

    fun show(
        context: Context,
        icon: Drawable?,
        text: String,
        contentIntent: PendingIntent?,
        backgroundColor: Int,
    ) {
        val app = context.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return

        // TYPE_ACCESSIBILITY_OVERLAY windows must be added via a WindowManager scoped to a
        // running AccessibilityService - a plain application Context can't add one. No
        // service connected (accessibility disabled) means no ticker.
        val service = Q25AccessibilityService.instance ?: return

        mainHandler.post {
            hideInternal()

            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = LayoutInflater.from(service).inflate(R.layout.ticker_overlay, null)
            val iconView = view.findViewById<ImageView>(R.id.ticker_icon)
            val iconContainer = view.findViewById<View>(R.id.ticker_icon_container)
            val textView = view.findViewById<TextView>(R.id.ticker_text)

            view.setBackgroundColor(backgroundColor)
            if (icon != null) {
                iconView.setImageDrawable(icon)
                iconView.alpha = 1f
                iconView.visibility = View.VISIBLE
                iconContainer?.visibility = View.VISIBLE
                iconContainer?.setBackgroundColor(backgroundColor)
            } else {
                iconView.visibility = View.GONE
                iconContainer?.visibility = View.GONE
            }
            textView.text = text
            textView.translationX = 0f

            attachGestures(service, view, contentIntent)

            // The dimen-resource guess below is only a starting point for the very first
            // frame; setOnApplyWindowInsetsListener corrects it to the real, live status
            // bar height for this device/orientation/notch as soon as the system reports
            // it, which is the actual DPI/device-independent source of truth.
            //
            // FLAG_LAYOUT_NO_LIMITS | FLAG_LAYOUT_IN_SCREEN is the flag combination used for
            // this same window type, plus FLAG_NOT_TOUCH_MODAL so the bar itself receives
            // touches while everything outside it passes straight through. It is always
            // touchable now, not just when there's a contentIntent to tap: swipe-down-to-open-
            // the-shade has to work on every ticker, otherwise the ticker silently eats the
            // status bar's own pull-down gesture for as long as it's on screen.
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                fallbackStatusBarHeight(service.resources),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP }

            ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val statusBarPx = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                if (statusBarPx > 0 && params.height != statusBarPx) {
                    params.height = statusBarPx
                    try {
                        windowManager?.updateViewLayout(v, params)
                    } catch (_: IllegalArgumentException) {
                        // View already detached by the time insets arrived - ignore.
                    }
                }
                insets
            }

            try {
                wm.addView(view, params)
            } catch (_: Exception) {
                // Accessibility service disconnected mid-flight - nothing to show.
                return@post
            }
            windowManager = wm
            overlayView = view

            view.post { startScroll(service, textView, view) }
        }
    }

    private fun startScroll(context: Context, textView: TextView, root: View) {
        if (root.width <= 0) {
            hideInternal()
            return
        }
        val textWidth = textView.paint.measureText(textView.text.toString())

        // Force the TextView to actually measure at its full natural width even if that's
        // wider than the screen - otherwise the parent clips its measured (not just drawn)
        // width down to what's visible, leaving nothing to reveal once it scrolls.
        textView.layoutParams = textView.layoutParams.apply { width = ceil(textWidth).toInt() + 4 }
        textView.requestLayout()

        val density = context.resources.displayMetrics.density
        val speedPxPerSec = (TickerSettings.scrollSpeedDpPerSec(context) * density).coerceAtLeast(1f)
        val startDelayMs = TickerSettings.startDelayMs(context).toLong()

        // The text starts at its natural laid-out position (icon, then text right after
        // it - fully visible, nothing scrolled yet) and stays put for startDelayMs. Once
        // that elapses it slides left far enough to clear the screen entirely, i.e. past
        // its own starting X plus its full width.
        val travelDistance = textView.left.toFloat() + textWidth
        val durationMs = ((travelDistance / speedPxPerSec) * 1000).toLong().coerceAtLeast(500L)

        val anim = ObjectAnimator.ofFloat(textView, View.TRANSLATION_X, 0f, -travelDistance)
        anim.startDelay = startDelayMs
        anim.duration = durationMs
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = hideInternal()
        })
        animator = anim
        anim.start()

        // Icon remains visible while the text scrolls underneath it, so we do not fade it out.

        // Safety-net in case the animator never reports completion (e.g. the window is
        // torn down from under it) - otherwise a stuck ticker would linger forever.
        val runnable = Runnable { hideInternal() }
        dismissRunnable = runnable
        mainHandler.postDelayed(runnable, startDelayMs + durationMs + 2000)
    }

    private fun hideInternal() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        animator?.cancel()
        animator = null
        iconAnimator?.cancel()
        iconAnimator = null
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager?.removeView(view)
        } catch (_: IllegalArgumentException) {
            // Already detached.
        }
        // Hand heads-up popups back, after a short grace period and on TickerController's own
        // thread - never inline here, which is the main looper (see TickerController for what
        // blocking it does to physical-keyboard input).
        TickerController.releaseHeadsUpAfterTicker()
    }

    private fun fallbackStatusBarHeight(resources: Resources): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) {
            resources.getDimensionPixelSize(resId)
        } else {
            (24 * resources.displayMetrics.density).toInt()
        }
    }
}
