package com.kgr.q25toolbox.service

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.animation.PathInterpolator
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.SlimRecentsController
import com.kgr.q25toolbox.modules.SlimRecentsController.SlimTask
import java.util.concurrent.Executors

/**
 * "Slim List" Recents: a full-screen TYPE_ACCESSIBILITY_OVERLAY window (same
 * window trick as [TickerOverlayController]) showing a vertical, thumbnail-
 * free list of running tasks. Entirely standalone - no launcher hook, no
 * RecentsView involvement; showing/hiding this window is the whole feature,
 * since nothing about the real foreground app ever changes while it's open.
 *
 * Tap a row to resume that task (see [SlimRecentsController.resumeTask] for
 * why that's not a plain relaunch). Swipe a row away to dismiss that task.
 * Tap the empty background, or Back, to close the list with no change
 * underneath.
 */
object SlimRecentsOverlayController {

    /** Lean-row corner radius, matching stock Overview card rounding. Masonry
     *  cards are square (BlackBerry productivity-tab look). */
    private const val CORNER_RADIUS_DP = 20

    /** Masonry: a 3-wide quilt of SQUARE tiles - span 1 = 1x1, span 2 = 2x2,
     *  span 3 = 3x3 (the most-recent "hero"). */
    private const val MASONRY_COLUMNS = 3

    /** Fixed name-strip height (dp) - icon + name + close, above the snapshot. */
    private const val CARD_HEADER_DP = 30

    /** Masonry tiles at least this wide (dp) show the action buttons inline; on this device (508 dp wide) a 1-cell tile is ~165 dp and a 2-cell one ~335 dp. */
    private const val WIDE_TILE_DP = 220

    // Open/close motion: a quick scale-up + fade with the Material "emphasized" curves, close to the
    // system Overview entrance. Scale is subtle because the whole window scales, not individual tiles.
    private const val OPEN_SCALE = 0.93f
    private const val OPEN_MS = 280L
    private const val CLOSE_SCALE = 0.96f
    private const val CLOSE_MS = 170L
    /** How long a chosen app may take to come to the front before the exit runs anyway. */
    private const val LAUNCH_WAIT_MS = 900L

    /** Masonry open: the newest tile (the app you were in) shrinks from full screen into place while the rest fade in. */
    private const val ENTRANCE_MS = 340L
    /**
     * Masonry close: the chosen tile (or the newest, on Back) grows to full screen while the window fades out
     * over the last part of the same time, so the whole exit is one phase. Shorter than the entrance on purpose:
     * the user is waiting for the app, and the app is usually already back behind the overlay by then.
     */
    private const val EXPAND_MS = 230L
    private const val EXPAND_FADE_START = 0.4f
    /** Extra fade after the growth has finished, so the hand-over to the app is soft rather than a cut. */
    private const val EXPAND_FADE_TAIL_MS = 60L

    /** Default for [hide]'s expandTaskId: grow the newest tile. Pass null for a plain fade. */
    const val EXPAND_HERO = Int.MIN_VALUE

    // Resume / dismiss issue root shell commands - never run those on the
    // main thread that's servicing row touch events.
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var windowManager: WindowManager? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var root: FrameLayout? = null
    private var currentTasks: List<SlimTask> = emptyList()
    private var currentSnapshots: Map<Int, android.graphics.Bitmap> = emptyMap()
    private var cardsMode: Boolean = false
    private val thumbViews = HashMap<Int, ImageView>()
    // Masonry only: taskId -> the whole tile / its name strip, used by the shared-element open/close motion.
    private val cardViews = HashMap<Int, View>()
    private val headerViews = HashMap<Int, View>()
    /** Task ids whose tile is being dismissed; see [dismissTile]. Cleared whenever the tiles are rebuilt. */
    private val dismissing = HashSet<Int>()
    private var pendingEntrance = false
    private var menuScrim: View? = null
    private var menuCard: View? = null

    fun isShowing(): Boolean = root != null

    /**
     * Dispatches [block] on [ioExecutor], catching anything it throws. An
     * uncaught exception on ANY thread - not just main - kills the whole
     * Android process by default, which would tear down every window this
     * process owns, including the unrelated Ticker overlay. A failed
     * resume/dismiss should just fail quietly, never take the app down.
     */
    private fun runSafely(block: () -> Unit) {
        ioExecutor.execute {
            try {
                block()
            } catch (t: Throwable) {
                Log.e("Q25Toolbox", "SlimRecents background action failed", t)
            }
        }
    }

    /**
     * Same idea as [runSafely] but for code that must run on the main thread
     * (all view construction/touch handling here does) - an uncaught
     * exception here is just as fatal to the whole process as one in a
     * background executor. Every listener and public entry point below goes
     * through this; a failed UI action should degrade to "nothing visibly
     * happened", never take the Ticker overlay down with it.
     */
    private fun safeUi(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e("Q25Toolbox", "SlimRecents UI action failed", t)
        }
    }

    private fun openAppInfo(svc: AccessibilityService, pkg: String) {
        try {
            svc.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Small anchored quick-actions card, same idea as stock Overview's icon-tap
     * menu - but that one is private launcher UI (TaskMenuView), not something
     * a third-party app can invoke, so this is our own. Holds the same actions as the inline buttons on wide Masonry tiles
     * ([TileAction]); it is the only way to reach them from a 1x1 tile or a Slim List row, which have no room for them. Lives as a couple of
     * extra children on [root] (drawn last = on top of everything), not a
     * PopupWindow - a PopupWindow's own focus/token handling gets awkward
     * stacked inside a TYPE_ACCESSIBILITY_OVERLAY, and this needs nothing that
     * a plain view can't already do.
     */
    private fun showIconMenu(svc: AccessibilityService, anchor: View, task: SlimTask) {
        closeIconMenu()
        val container = root ?: return
        val density = svc.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val screenW = svc.resources.displayMetrics.widthPixels
        val cardWidthGuess = px(160)
        val left = loc[0].coerceIn(0, (screenW - cardWidthGuess).coerceAtLeast(0))
        val top = loc[1] + anchor.height

        val scrim = View(svc).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { safeUi { closeIconMenu() } }
        }
        container.addView(
            scrim,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = px(CORNER_RADIUS_DP).toFloat()
                setColor(Color.argb(250, 45, 45, 45))
            }
        }
        TileAction.entries.forEach { action ->
            card.addView(TextView(svc).apply {
                text = svc.getString(action.labelRes)
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(px(20), px(14), px(20), px(14))
                isClickable = true
                background = selectableBg(svc, borderless = false)
                setOnClickListener { safeUi { runTileAction(svc, task, action) } }
            })
        }

        container.addView(
            card,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = left
                topMargin = top
            }
        )
        menuScrim = scrim
        menuCard = card
    }

    /**
     * Length for a view-property animation: [baseMs] x the user's setting x the system animator scale.
     * ViewPropertyAnimator's own ValueAnimator multiplies by [ValueAnimator.getDurationScale] on top, so
     * that factor is divided out here; whichever way the process picked the system scale up, the effective
     * time ends up as base x user x system exactly once. 0 (animations off) gives an instant change.
     */
    private fun animMs(ctx: Context, baseMs: Long): Long {
        val sp = ctx.getSharedPreferences(com.kgr.q25toolbox.service.Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
        val user = SlimRecentsController.animDurationPercent(sp) / 100f
        val sys = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val applied = ValueAnimator.getDurationScale()
        if (user <= 0f || sys <= 0f || applied <= 0f) return 0L
        return (baseMs * user * sys / applied).toLong()
    }

    /** App that was in the foreground when this overlay opened (null if unknown). See [wireRow]. */
    private var frontPackage: String? = null

    private fun closeIconMenu() {
        menuCard?.let { (it.parent as? ViewGroup)?.removeView(it) }
        menuScrim?.let { (it.parent as? ViewGroup)?.removeView(it) }
        menuCard = null
        menuScrim = null
    }

    /**
     * [tasks] must already be fetched off the main thread - see
     * [SlimRecentsController.listTasks]. [snapshots] (Masonry mode) maps task id
     * to its last snapshot bitmap - also loaded off the main thread; empty for
     * Slim List, and any task missing from it just renders a lean, thumbnail-
     * free row.
     */
    /**
     * Show (or refresh) the list. [cards] = Masonry (two-column snapshot grid);
     * false = Slim List (lean rows). In Masonry mode the window comes up right
     * away with placeholder cards - snapshots are loaded off the main thread and
     * pushed in afterwards via [fillSnapshots].
     */
    fun show(
        svc: AccessibilityService,
        tasks: List<SlimTask>,
        cards: Boolean = false,
        frontPkg: String? = null,
    ) = safeUi {
        cardsMode = cards
        // Package that was in front when Recents was opened. Captured by the caller BEFORE the window goes up,
        // so it cannot be confused with this overlay; kept across refreshes while the window stays showing.
        if (root == null) frontPackage = frontPkg
        if (!cards) currentSnapshots = emptyMap()
        Log.d("Q25Toolbox", "SlimRecents.show: ${tasks.size} tasks, mode=${if (cards) "cards" else "lean"}")
        if (root != null) {
            rebuildRows(svc, tasks)
        } else {
            attach(svc, tasks)
        }
    }

    /**
     * Closes the overlay. [animate] plays a short fade/scale-out (used for user-driven closes); screen-off
     * and service teardown pass false, since animations do not run with the screen off and the window must
     * be gone immediately. [isShowing] turns false at once either way.
     */
    // The app the user picked is starting: the overlay stays up until it is in front (or [LAUNCH_WAIT_MS] passes), so the
    // exit animation reveals an app that is already there instead of racing its own launch transition.
    @Volatile private var pendingLaunchPkg: String? = null
    @Volatile private var pendingLaunchTaskId: Int? = null

    /** Called by the service when the foreground app changes. */
    fun onForegroundChanged(pkg: String) {
        if (root != null && pkg == pendingLaunchPkg) hide(expandTaskId = pendingLaunchTaskId)
    }

    fun hide(animate: Boolean = true, expandTaskId: Int? = EXPAND_HERO) = safeUi {
        pendingLaunchPkg = null
        pendingLaunchTaskId = null
        closeIconMenu()
        val shots = currentSnapshots // before it is cleared: decides whether a tile may expand (see below)
        currentSnapshots = emptyMap()
        val cards = HashMap(cardViews)
        val thumbs = HashMap(thumbViews)
        val headers = HashMap(headerViews)
        val heroId = currentTasks.firstOrNull()?.taskId
        thumbViews.clear(); cardViews.clear(); headerViews.clear()
        pendingEntrance = false
        val v = root
        root = null
        if (v != null) {
            val wm = windowManager
            val params = windowParams
            val remove: () -> Unit = { OverlayWindow.removeSoftly(wm, v, params) }
            // Only a tile with a real snapshot may grow to full screen: one without it is a dark placeholder, and
            // expanding it would cover the screen with a black rectangle. Plain fade then.
            val expandId = expandTaskId?.let { if (it == EXPAND_HERO) heroId else it }
            val canExpand = expandId != null && shots.containsKey(expandId)
            val target = if (canExpand) cards[expandId] else null
            val targetThumb = if (canExpand) thumbs[expandId] else null
            v.animate().cancel()
            // Duration 0 (setting or system animator scale off): no animators at all, just drop the window.
            val animating = animate && animMs(v.context, CLOSE_MS) > 0L
            if (animating && cardsMode && target != null && targetThumb != null) {
                OverlayWindow.blurOff(wm, v, params)
                expandAndFade(v, cards, headers, target, targetThumb, remove)
            } else if (animating) {
                OverlayWindow.blurOff(wm, v, params)
                v.animate()
                    .alpha(0f).scaleX(CLOSE_SCALE).scaleY(CLOSE_SCALE)
                    .setDuration(animMs(v.context, CLOSE_MS))
                    .setInterpolator(PathInterpolator(0.3f, 0f, 0.8f, 0.15f)) // emphasized accelerate
                    .withEndAction { safeUi(remove) }
                    .start()
            } else remove()
        }
    }

    /**
     * Transform that makes [thumb] (inside [card]) cover the whole screen: uniform "cover" scale around the
     * thumbnail's centre, then a translation moving that centre to the screen centre. Applying it to [card]
     * makes the tile look like the app's own full-screen window; clearing it returns the tile to its slot.
     */
    private class CoverTransform(val scale: Float, val dx: Float, val dy: Float, val pivotX: Float, val pivotY: Float)

    private fun coverTransform(ctx: Context, thumb: View): CoverTransform? {
        if (thumb.width <= 0 || thumb.height <= 0) return null
        val dm = ctx.resources.displayMetrics
        val loc = IntArray(2)
        thumb.getLocationOnScreen(loc)
        val cx = loc[0] + thumb.width / 2f
        val cy = loc[1] + thumb.height / 2f
        val scale = maxOf(dm.widthPixels / thumb.width.toFloat(), dm.heightPixels / thumb.height.toFloat())
        return CoverTransform(scale, dm.widthPixels / 2f - cx, dm.heightPixels / 2f - cy,
            thumb.left + thumb.width / 2f, thumb.top + thumb.height / 2f)
    }

    private fun expandAndFade(
        window: View, cards: Map<Int, View>, headers: Map<Int, View>,
        target: View, thumb: View, remove: () -> Unit,
    ) {
        val ctx = window.context
        val t = coverTransform(ctx, thumb)
        if (t == null) { remove(); return }
        val dur = animMs(ctx, EXPAND_MS)
        val ease = PathInterpolator(0.2f, 0f, 0f, 1f) // emphasized
        cards.values.filter { it !== target }.forEach {
            it.animate().alpha(0f).setDuration(dur / 2).start()
        }
        (headers.entries.firstOrNull { cards[it.key] === target })?.value
            ?.animate()?.alpha(0f)?.setDuration(dur / 2)?.start()
        target.translationZ = 8f
        target.pivotX = t.pivotX
        target.pivotY = t.pivotY
        target.animate()
            .scaleX(t.scale).scaleY(t.scale).translationX(t.dx).translationY(t.dy)
            .setDuration(dur).setInterpolator(ease)
            .start()
        // The window starts fading partway through the growth and finishes a moment after it, then is removed.
        val fadeStart = (dur * EXPAND_FADE_START).toLong()
        window.animate().alpha(0f).setStartDelay(fadeStart)
            .setDuration(dur - fadeStart + animMs(ctx, EXPAND_FADE_TAIL_MS))
            .withEndAction { safeUi(remove) }.start()
        // The scrim goes with the other tiles, so the growing tile ends up over whatever is behind it.
        (window.background as? android.graphics.drawable.ColorDrawable)?.let { bg ->
            ValueAnimator.ofInt(bg.alpha, 0).apply {
                duration = dur
                addUpdateListener { bg.alpha = it.animatedValue as Int }
                start()
            }
        }
    }

    /** Masonry open: see [ENTRANCE_MS]. Runs once, on the first layout pass, before the first frame is drawn. */
    private fun playEntrance(svc: AccessibilityService) {
        val window = root ?: return
        val heroId = currentTasks.firstOrNull()?.taskId
        val hero = heroId?.let { cardViews[it] }
        val thumb = heroId?.let { thumbViews[it] }
        val dur = animMs(svc, ENTRANCE_MS)
        if (dur <= 0L) return // animations off: tiles stay where they are, scrim stays at full alpha
        val ease = PathInterpolator(0.05f, 0.7f, 0.1f, 1f) // emphasized decelerate
        val density = svc.resources.displayMetrics.density

        var order = 0
        cardViews.forEach { (_, v) ->
            if (v === hero) return@forEach
            v.alpha = 0f
            v.translationY = 28 * density
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay((dur / 5) + order * 22L).setDuration(dur).setInterpolator(ease).start()
            order++
        }
        (window.background as? android.graphics.drawable.ColorDrawable)?.let { bg ->
            val full = bg.alpha
            bg.alpha = 0
            ValueAnimator.ofInt(0, full).apply {
                duration = dur
                addUpdateListener { bg.alpha = it.animatedValue as Int }
                start()
            }
        }
        val t = if (hero != null && thumb != null) coverTransform(svc, thumb) else null
        if (hero == null || t == null) return
        hero.translationZ = 8f
        hero.pivotX = t.pivotX
        hero.pivotY = t.pivotY
        hero.scaleX = t.scale; hero.scaleY = t.scale
        hero.translationX = t.dx; hero.translationY = t.dy
        headerViews[heroId]?.apply { alpha = 0f; animate().alpha(1f).setStartDelay(dur / 2).setDuration(dur / 2).start() }
        hero.animate()
            .scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
            .setDuration(dur).setInterpolator(ease)
            .withEndAction { hero.translationZ = 0f }
            .start()
    }

    // ------------------------------------------------------------- internals

    /** No on-screen toolbelt in this app (the Q25 has physical keys), so nothing to keep clear of. */
    @Suppress("UNUSED_PARAMETER")
    private fun beltInsetPx(ctx: Context): Int = 0

    private fun attach(svc: AccessibilityService, tasks: List<SlimTask>) {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val belt = beltInsetPx(svc)

        val container = FrameLayout(svc).apply {
            setBackgroundColor(SlimRecentsController.scrimColor(svc))
            clipChildren = false // the newest tile scales beyond its slot during open/close (cards mode)
        }

        val list = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * svc.resources.displayMetrics.density).toInt()
            // room for the Close All button + the toolbelt strip below it
            val bottomClearance = (88 * svc.resources.displayMetrics.density).toInt() + belt
            setPadding(pad, pad, pad, bottomClearance)
            clipChildren = false
            clipToPadding = false
        }
        val scroller = ScrollView(svc).apply {
            isFillViewport = true
            clipChildren = false
            clipToPadding = false
            addView(list, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Tap empty space (anywhere not on a row - rows get first dispatch as
        // children and consume their own touch sequence) to close, same as
        // Back. A plain OnClickListener on `container` doesn't work here: this
        // fullscreen ScrollView sits on top of it and claims every touch for
        // its own gesture handling before a parent's click listener would ever
        // see it, even over the "empty" unscrollable area. Returning false
        // lets a genuine scroll/fling still reach ScrollView's own handling.
        val bgSlop = ViewConfiguration.get(svc).scaledTouchSlop
        var bgDownX = 0f
        var bgDownY = 0f
        var bgMoved = false
        scroller.setOnTouchListener { _, ev ->
            try {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        bgDownX = ev.rawX; bgDownY = ev.rawY; bgMoved = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(ev.rawX - bgDownX) > bgSlop ||
                            kotlin.math.abs(ev.rawY - bgDownY) > bgSlop
                        ) bgMoved = true
                        false
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!bgMoved) hide()
                        false
                    }
                    else -> false
                }
            } catch (t: Throwable) {
                Log.e("Q25Toolbox", "SlimRecents background touch failed", t)
                false
            }
        }
        container.addView(
            scroller,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val density = svc.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val closeAll = TextView(svc).apply {
            text = svc.getString(R.string.recents_slim_close_all)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(px(24), px(12), px(24), px(12))
            background = GradientDrawable().apply {
                cornerRadius = px(CORNER_RADIUS_DP).toFloat()
                setColor(Color.argb(230, 140, 30, 30))
            }
            isClickable = true
            setOnClickListener {
                safeUi {
                    val tasks = currentTasks
                    runSafely { SlimRecentsController.dismissAll(tasks) }
                    hide(expandTaskId = null)
                }
            }
        }
        container.addView(
            closeAll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = px(24) + belt
            }
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
            // Optional blur of whatever is behind the overlay (setting in Recents). Only when the system
            // allows cross-window blur (off under battery saver or on devices without the feature).
            val blur = SlimRecentsController.scrimBlurRadiusPx(svc)
            if (blur > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm.isCrossWindowBlurEnabled) {
                // FLAG_DIM_BEHIND is needed too: the system only reads dimAmount when it is set.
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                blurBehindRadius = blur
                // Blur-behind is drawn through the window's dim layer, whose alpha comes from dimAmount (0 by
                // default). Verified with dumpsys on LineageOS 23: WM and SurfaceFlinger both carried
                // backgroundBlurRadius=72, but the layer had alpha=0 and nothing was blurred. A 1% dim is
                // invisible and gives the layer a non-zero alpha (dimAmount alone had no effect without DIM_BEHIND).
                dimAmount = 0.01f
            }
        }

        // Slim List: the whole window starts invisible and slightly small, then eases in (see OPEN_*).
        // Masonry: the window stays as is and the tiles do the moving (see playEntrance).
        val openMs = animMs(svc, OPEN_MS)
        if (!cardsMode && openMs > 0L) {
            container.alpha = 0f
            container.scaleX = OPEN_SCALE
            container.scaleY = OPEN_SCALE
        }
        try {
            wm.addView(container, lp)
        } catch (_: Exception) {
            return
        }
        if (!cardsMode && openMs > 0L) {
            container.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(openMs)
                .setInterpolator(PathInterpolator(0.05f, 0.7f, 0.1f, 1f)) // emphasized decelerate
                .start()
        }
        pendingEntrance = cardsMode
        windowManager = wm
        windowParams = lp
        root = container
        buildRows(svc, list, tasks)
    }

    private fun rebuildRows(svc: AccessibilityService, tasks: List<SlimTask>) {
        val scroller = root?.getChildAt(0) as? ScrollView ?: return
        val list = scroller.getChildAt(0) as? LinearLayout ?: return
        buildRows(svc, list, tasks)
    }

    private fun buildRows(svc: AccessibilityService, list: LinearLayout, tasks: List<SlimTask>) {
        closeIconMenu()
        currentTasks = tasks
        thumbViews.clear(); cardViews.clear(); headerViews.clear(); dismissing.clear()
        list.removeAllViews()
        val density = svc.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        if (tasks.isEmpty()) {
            val empty = TextView(svc).apply {
                text = svc.getString(R.string.recents_slim_empty)
                setTextColor(Color.LTGRAY)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, px(32), 0, 0)
            }
            list.addView(empty)
            return
        }

        if (cardsMode) buildCardMasonry(svc, list, tasks, ::px)
        else buildLeanColumn(svc, list, tasks, ::px)
    }

    /** Slim List: one vertical column, most recent at the bottom (thumb reach). */
    private fun buildLeanColumn(
        svc: AccessibilityService, list: LinearLayout, tasks: List<SlimTask>, px: (Int) -> Int,
    ) {
        tasks.asReversed().forEach { task ->
            list.addView(
                buildLeanRow(svc, task),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = px(4) }
            )
        }
        (list.parent as? ScrollView)?.let(::landAtBottom)
    }

    /** Jump to the bottom (newest) before the first frame draws - no visible auto-scroll. */
    /** The accessibility service owning [v]'s window (its views are all created with it as context). */
    private fun svcRef(v: View): AccessibilityService = v.context as AccessibilityService

    private fun landAtBottom(sv: ScrollView) {
        sv.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sv.viewTreeObserver.removeOnPreDrawListener(this)
                sv.scrollTo(0, sv.getChildAt(0)?.height ?: 0)
                return true
            }
        })
    }

    /** A tile placed within a 3-wide block: grid position + span (in grid cells). */
    private data class Tile(val task: SlimTask, val col: Int, val row: Int, val w: Int, val h: Int)

    /** A gap-free 3-column-wide strip of tiles, [rows] cells tall (1 or 2). */
    private data class Block(val tiles: List<Tile>, val rows: Int)

    /**
     * Pack tasks (most-recent-first) into gap-free 3-wide blocks: the newest is
     * a 3x2 hero, then repeating {2x2 + two 1x1} / {two 1x1 + 2x2} / rows of
     * 1x1 for variety. Blocks render bottom-to-top so the newest sits at the
     * bottom near the thumb.
     */
    private fun packBlocks(tasks: List<SlimTask>): List<Block> {
        val q = ArrayDeque(tasks)
        val out = ArrayList<Block>()
        q.removeFirstOrNull()?.let { out.add(Block(listOf(Tile(it, 0, 0, 3, 2)), 2)) }

        var idx = 0
        while (q.isNotEmpty()) {
            when {
                q.size >= 3 -> when (idx % 3) {
                    0 -> out.add(
                        Block(
                            listOf(
                                Tile(q.removeFirst(), 0, 0, 2, 2),
                                Tile(q.removeFirst(), 2, 0, 1, 1),
                                Tile(q.removeFirst(), 2, 1, 1, 1),
                            ), 2,
                        )
                    )
                    1 -> out.add(
                        Block(
                            listOf(
                                Tile(q.removeFirst(), 0, 0, 1, 1),
                                Tile(q.removeFirst(), 0, 1, 1, 1),
                                Tile(q.removeFirst(), 1, 0, 2, 2),
                            ), 2,
                        )
                    )
                    else -> {
                        val n = minOf(6, q.size)
                        out.add(Block((0 until n).map { i -> Tile(q.removeFirst(), i % 3, i / 3, 1, 1) }, if (n > 3) 2 else 1))
                    }
                }
                q.size == 2 -> out.add(
                    Block(listOf(Tile(q.removeFirst(), 0, 0, 1, 1), Tile(q.removeFirst(), 1, 0, 2, 1)), 1)
                )
                else -> out.add(Block(listOf(Tile(q.removeFirst(), 0, 0, 3, 1)), 1)) // lone oldest -> full-width strip
            }
            idx++
        }
        return out
    }

    /**
     * Masonry: a gap-free 3-wide quilt of blocks, newest at the bottom (scroll
     * up for older). Positioned absolutely in a FrameLayout; snapshots stream in
     * later via [fillSnapshots].
     */
    private fun buildCardMasonry(
        svc: AccessibilityService, list: LinearLayout, tasks: List<SlimTask>, px: (Int) -> Int,
    ) {
        val gap = px(3)
        val contentW = svc.resources.displayMetrics.widthPixels - list.paddingLeft - list.paddingRight
        val colW = ((contentW - gap * (MASONRY_COLUMNS - 1)) / MASONRY_COLUMNS).coerceAtLeast(px(48))
        fun cell(n: Int) = n * colW + (n - 1) * gap

        val quilt = FrameLayout(svc).apply { clipChildren = false }
        list.addView(quilt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val blocks = packBlocks(tasks)
        val totalH = blocks.sumOf { cell(it.rows) + gap } - gap
        var blockTop = totalH
        for (block in blocks) { // blocks[0] = hero, goes at the very bottom
            blockTop -= cell(block.rows)
            for (t in block.tiles) {
                quilt.addView(
                    buildCard(svc, t.task, px, cell(t.w), cell(t.h)),
                    FrameLayout.LayoutParams(cell(t.w), cell(t.h)).apply {
                        leftMargin = t.col * (colW + gap)
                        topMargin = blockTop + t.row * (colW + gap)
                    },
                )
            }
            blockTop -= gap
        }
        quilt.minimumHeight = totalH.coerceAtLeast(0)
        (list.parent as? ScrollView)?.let { sv ->
            landAtBottom(sv)
            if (pendingEntrance) {
                // Registered after landAtBottom's listener, so the scroll position is final when this runs.
                sv.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        sv.viewTreeObserver.removeOnPreDrawListener(this)
                        pendingEntrance = false
                        safeUi { playEntrance(svcRef(sv)) }
                        return true
                    }
                })
            }
        }
    }

    /** Fill (or update) the streamed-in snapshots. Missing ids keep their placeholder. */
    fun fillSnapshots(snapshots: Map<Int, android.graphics.Bitmap>) = safeUi {
        Log.d("Q25Toolbox", "SlimRecents.fillSnapshots: ${snapshots.size} in, ${thumbViews.size} slots")
        currentSnapshots = currentSnapshots + snapshots // merge - callers stream partial sets
        snapshots.forEach { (id, bmp) ->
            thumbViews[id]?.apply {
                setImageBitmap(bmp)
                setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    // --- one task's view ------------------------------------------------

    /** Slim List row: a rounded dark pill, icon + label + close, horizontal. */
    private fun buildLeanRow(svc: AccessibilityService, task: SlimTask): View {
        val density = svc.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = px(CORNER_RADIUS_DP).toFloat()
                setColor(Color.argb(230, 30, 30, 30))
            }
            setPadding(px(16), px(14), px(16), px(14))
            isClickable = true
        }
        row.addView(
            appIcon(svc, task, px(40)),
            LinearLayout.LayoutParams(px(40), px(40)).apply { rightMargin = px(16) },
        )
        row.addView(appLabel(svc, task, 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(closeBtn(svc, task) { row }, LinearLayout.LayoutParams(px(40), px(40)).apply { leftMargin = px(8) })
        wireRow(svc, row, task)
        return row
    }

    /**
     * Masonry tile of an explicit [w] x [h]: a name strip tinted with the app's
     * muted icon colour, above the snapshot filling the rest of the tile.
     */
    private fun buildCard(svc: AccessibilityService, task: SlimTask, px: (Int) -> Int, w: Int, h: Int): View {
        val headerPx = px(CARD_HEADER_DP)
        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            // Square by default; the radius is a setting (Recents > Appearance). clipToOutline rounds the header strip too.
            background = GradientDrawable().apply { cornerRadius = SlimRecentsController.quiltCornerPx(svc).toFloat(); setColor(Color.rgb(18, 18, 18)) }
            clipToOutline = true
            isClickable = true
        }

        val header = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(SlimRecentsController.bannerColor(task.packageName, task.icon))
            setPadding(px(8), 0, px(2), 0)
        }
        header.addView(
            appIcon(svc, task, px(18)),
            LinearLayout.LayoutParams(px(18), px(18)).apply { rightMargin = px(6) },
        )
        header.addView(appLabel(svc, task, 12f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Wide tiles (2 cells and up) have room for the actions inline; a 1x1 tile reaches them through the icon menu.
        if (w >= px(WIDE_TILE_DP)) {
            TileAction.entries.forEach {
                header.addView(tileActionBtn(svc, task, it, headerPx, px(7)), LinearLayout.LayoutParams(headerPx, headerPx))
            }
        }
        header.addView(closeBtn(svc, task) { card }, LinearLayout.LayoutParams(headerPx, headerPx))
        headerViews[task.taskId] = header
        card.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerPx))

        val thumb = ImageView(svc).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(30, 30, 30)) // placeholder until fillSnapshots
            currentSnapshots[task.taskId]?.let { setImageBitmap(it); setBackgroundColor(Color.TRANSPARENT) }
        }
        thumbViews[task.taskId] = thumb
        card.addView(thumb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (h - headerPx).coerceAtLeast(0)))

        cardViews[task.taskId] = card
        wireRow(svc, card, task)
        return card
    }

    // --- shared row pieces --------------------------------------------

    private fun selectableBg(svc: AccessibilityService, borderless: Boolean) = TypedValue().let { out ->
        val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless
        else android.R.attr.selectableItemBackground
        if (svc.theme.resolveAttribute(attr, out, true)) svc.getDrawable(out.resourceId) else null
    }

    private fun appIcon(svc: AccessibilityService, task: SlimTask, @Suppress("UNUSED_PARAMETER") size: Int) =
        ImageView(svc).apply {
            task.icon?.let { setImageDrawable(it) }
            isClickable = true
            background = selectableBg(svc, borderless = true)
            setOnClickListener { safeUi { showIconMenu(svc, this, task) } }
        }

    private fun appLabel(svc: AccessibilityService, task: SlimTask, sizeSp: Float) = TextView(svc).apply {
        text = task.label
        setTextColor(Color.WHITE)
        textSize = sizeSp
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /**
     * Closes [task] and drops its view, exactly once. ViewGroup.removeView first sends ACTION_CANCEL to a view that is
     * the current touch target, BEFORE it takes the view out of its child array. For a swiped tile that cancel re-enters
     * the touch listener, which dismissed again: the nested removal shifted the array, and the outer removal then
     * dropped the NEXT sibling by its stale index - a neighbour tile vanished though its task was never closed.
     * [dismissing] makes the second entry a no-op.
     */
    private fun dismissTile(v: View, task: SlimTask) {
        if (!dismissing.add(task.taskId)) return
        runSafely { SlimRecentsController.dismissTask(task) }
        cardViews.remove(task.taskId); headerViews.remove(task.taskId); thumbViews.remove(task.taskId)
        (v.parent as? ViewGroup)?.removeView(v)
    }

    /** Per-task actions, offered as inline header buttons on wide Masonry tiles and as rows of the icon menu. */
    private enum class TileAction(val iconRes: Int, val labelRes: Int) {
        INFO(R.drawable.ic_recents_info, R.string.recents_slim_app_info),
        SPLIT(R.drawable.ic_recents_split, R.string.gesture_action_split_screen),
        SCREENSHOT(R.drawable.ic_recents_screenshot, R.string.gesture_action_screenshot),
    }

    private fun runTileAction(svc: AccessibilityService, task: SlimTask, action: TileAction) {
        when (action) {
            TileAction.INFO -> {
                closeIconMenu()
                openAppInfo(svc, task.packageName)
                hide(expandTaskId = null)
            }
            // Both act on whatever is in front, so the task goes to the front first. Split screen: the system puts
            // that app in the top pane and shows Overview to pick the second one.
            TileAction.SPLIT -> inFrontThen(svc, task) { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) }
            TileAction.SCREENSHOT -> inFrontThen(svc, task) { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }
        }
    }

    /**
     * Closes the overlay at once (it must not be in a screenshot), brings [task] to the front unless it already is,
     * and runs [action] after a fixed wait: a quarter second for the overlay to be gone, or [LAUNCH_WAIT_MS] for a
     * resumed app to be drawn. A timing heuristic, not a signal that the app is up.
     */
    private fun inFrontThen(svc: AccessibilityService, task: SlimTask, action: () -> Unit) {
        closeIconMenu()
        val alreadyFront = task.packageName == frontPackage && task.taskId == currentTasks.firstOrNull()?.taskId
        hide(animate = false, expandTaskId = null)
        if (!alreadyFront) runSafely { SlimRecentsController.resumeTask(task) }
        Handler(Looper.getMainLooper()).postDelayed({ safeUi(action) }, if (alreadyFront) 250L else LAUNCH_WAIT_MS)
    }

    private fun tileActionBtn(svc: AccessibilityService, task: SlimTask, action: TileAction, size: Int, pad: Int) =
        ImageView(svc).apply {
            setImageResource(action.iconRes)
            setColorFilter(Color.LTGRAY)
            setPadding(pad, pad, pad, pad)
            contentDescription = svc.getString(action.labelRes)
            isClickable = true
            background = selectableBg(svc, borderless = true)
            setOnClickListener { safeUi { runTileAction(svc, task, action) } }
        }

    private fun closeBtn(svc: AccessibilityService, task: SlimTask, rowOf: () -> View) = TextView(svc).apply {
        text = "\u2715"
        setTextColor(Color.LTGRAY)
        textSize = 16f
        gravity = Gravity.CENTER
        isClickable = true
        background = selectableBg(svc, borderless = true)
        setOnClickListener {
            safeUi {
                dismissTile(rowOf(), task)
            }
        }
    }

    /**
     * Tap (not on the icon / X) resumes the task; a horizontal drag past a
     * width fraction or a fast fling dismisses it, otherwise it springs back.
     */
    private fun wireRow(svc: AccessibilityService, row: View, task: SlimTask) {
        row.setOnClickListener {
            safeUi {
                // Already in front (the newest task and the app we came from): skip `am start`. The system would
                // log it as an activity restart attempt and re-run the launch transition right as the overlay
                // fades. Both conditions are required so a stale package can never swallow a real app switch.
                val alreadyFront = task.packageName == frontPackage && task.taskId == currentTasks.firstOrNull()?.taskId
                if (alreadyFront) {
                    hide(expandTaskId = task.taskId)
                } else {
                    runSafely { SlimRecentsController.resumeTask(task) }
                    val target = task.packageName
                    pendingLaunchPkg = target
                    pendingLaunchTaskId = task.taskId
                    val v = root
                    v?.postDelayed({
                        if (pendingLaunchPkg == target && root === v) hide(expandTaskId = task.taskId)
                    }, LAUNCH_WAIT_MS)
                }
            }
        }
        val slop = ViewConfiguration.get(svc).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var moved = false
        var vt: VelocityTracker? = null
        row.setOnTouchListener { v, ev ->
            try {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY; moved = false
                        vt = VelocityTracker.obtain().apply { addMovement(ev) }
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        vt?.addMovement(ev)
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (!moved && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            moved = true
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        if (moved) {
                            v.translationX = dx
                            v.alpha = (1f - kotlin.math.abs(dx) / v.width.coerceAtLeast(1)).coerceIn(0.2f, 1f)
                        }
                        moved
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        var vx = 0f
                        vt?.apply { addMovement(ev); computeCurrentVelocity(1000); vx = xVelocity; recycle() }
                        vt = null
                        val wasMoved = moved
                        moved = false // before any removal: the removal itself delivers a CANCEL to this listener
                        if (wasMoved) {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            val w = v.width.coerceAtLeast(1)
                            if (kotlin.math.abs(v.translationX) > w * 0.4f || kotlin.math.abs(vx) > 1200f) {
                                dismissTile(v, task)
                            } else {
                                v.animate().translationX(0f).alpha(1f).setDuration(150).start()
                            }
                        }
                        wasMoved
                    }
                    else -> false
                }
            } catch (t: Throwable) {
                Log.e("Q25Toolbox", "SlimRecents row touch failed", t)
                false
            }
        }
    }
}
