/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Always-on-top status bubble (SYSTEM_ALERT_WINDOW). Deliberately plain
 * android.widget views: this window lives outside the Activity/Compose tree, so
 * it has no lifecycle of its own and keeps working while the app is backgrounded
 * - which is the whole point (an overlay window keeps the app's process counted
 * as visible, so the VM keeps running after the user leaves the app).
 *
 * Interaction: tap the ball to expand a five-action panel, tap again to collapse.
 * Drag it anywhere; on release it snaps to the nearer screen edge, and it starts
 * out docked to the right edge (a little above the middle) so it never sits in
 * the way of the content underneath.
 *
 * Idle behaviour: a few seconds without a touch and the window shrinks from the
 * 44dp ball down to a [PEEK_SLIVER_DP] x [PEEK_SLIVER_HEIGHT_DP] tab pinned to
 * the docked edge (the ball view is hidden, so the window itself becomes 11dp
 * wide). A 44dp circle parked over a fullscreen video is an obstruction; a thin,
 * faint edge strip is not. Tapping the strip brings the whole ball back - that
 * tap does not open the panel, so the gesture always means "show me the ball
 * again".
 *
 * The window shrinks rather than sliding off the display: several ROMs clamp
 * overlay windows back inside the screen bounds, which silently turns
 * "x = screenWidth - 14dp" into a no-op (field report on v1.3.0-9: the ball
 * never moved). An 11dp window that sits at screenWidth - 11dp is fully on
 * screen, so nothing can clamp it away.
 */
package com.excp.podroid.service

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * @param statusLine one-line status shown in the expanded panel and copied by
 *   the "copy status" action; evaluated lazily so it never goes stale.
 * @param onOpenApp open the app itself.
 * @param onToggleVm start the VM when it is idle, stop it when it is up.
 * @param onRestartForwards replay every port-forward rule over QMP.
 * @param onHide hide the bubble until it is switched back on in Settings.
 */
class OverlayBubble(
    private val context: Context,
    private val statusLine: () -> String,
    private val onOpenApp: () -> Unit,
    private val onToggleVm: () -> Unit,
    private val onRestartForwards: () -> Unit,
    private val onHide: () -> Unit,
) {
    private companion object {
        const val TAG = "PodroidOverlay"
        const val BALL_DP = 44

        /** Gap between the ball and the panel when both are laid out. */
        const val BALL_MARGIN_DP = 6

        /** Where the ball starts vertically: 40% down the screen. */
        const val DOCK_Y_FRACTION = 0.40f

        /** Idle time before the ball slides off the edge, in milliseconds. */
        const val AUTO_PEEK_DELAY_MS = 4_000L

        /**
         * The peeking tab: narrower than the ball so it reads as a sliver at the
         * edge rather than a second widget.
         */
        const val PEEK_SLIVER_DP = 11

        /**
         * Peeking tab height. Shorter than [BALL_DP] on purpose - the tab is a
         * faint hint that something is docked there, not a button competing with
         * the content underneath.
         */
        const val PEEK_SLIVER_HEIGHT_DP = 32

        const val ROW_BG = 0x33FFFFFF

        // Not `const`: an `.toInt()` conversion is not a compile-time constant in
        // Kotlin, and these ARGB literals do not fit a positive Int.
        /** Panel background (near-black, ~90% opaque). */
        val PANEL_BG = 0xE6202124.toInt()

        /** Ball background (Podroid blue, ~80% opaque). */
        val BALL_BG = 0xCC2F6FED.toInt()

        /**
         * Peeking tab background: the same brand blue at ~35% alpha, so it is
         * only just perceptible against a video or a page ("隐隐约约").
         */
        val TAB_BG = 0x592F6FED.toInt()

        val FG = 0xFFE6E1E5.toInt()
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var params: WindowManager.LayoutParams? = null
    private var root: LinearLayout? = null
    private var panel: LinearLayout? = null
    private var ball: TextView? = null
    /** The "crack" shown while peeking: a narrow tab, the only visible child then. */
    private var tab: TextView? = null
    private var status: TextView? = null
    private var expanded = false

    /**
     * Which edge the ball is glued to. The panel is always laid out on the
     * opposite side, otherwise it would cover the ball it belongs to.
     */
    private var dockedLeft = false

    /** True while the ball is mostly off-screen, showing only a thin strip. */
    private var peeked = false

    private val idleHandler = Handler(Looper.getMainLooper())
    private val peekRunnable = Runnable { peekToEdge() }

    /**
     * Docking is an absolute x. Rotation changes the screen width underneath a
     * window that is already placed, so a tab pinned to the right edge in
     * portrait ended up near the middle of the landscape screen until the next
     * tap re-docked it. This callback re-applies the docked position whenever
     * the configuration changes. Registered for exactly as long as the window
     * exists, so it can never fire against a removed view.
     */
    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            reDockAfterRotation()
        }

        override fun onLowMemory() = Unit
    }

    fun isShowing(): Boolean = root != null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /** Adds the window; returns false when the grant is missing or the OEM refuses. */
    fun show(): Boolean {
        if (root != null) return true
        if (!canShow()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted; bubble stays hidden")
            return false
        }
        val view = buildView()
        val metrics = context.resources.displayMetrics
        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE keeps typing/back-key in the app underneath while the
            // window still receives touches (NOT_TOUCHABLE would eat the taps we
            // need). LAYOUT_IN_SCREEN makes x/y screen-relative, matching rawX/rawY.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Start docked on the RIGHT edge, a little above the vertical middle:
            // out of the way of centred content, and the panel - which opens
            // leftwards from there - has the whole screen width to expand into.
            // The exact x is recomputed from the measured width in dockToEdge()
            // once layout has run, so this is only the first-frame approximation.
            dockedLeft = false
            x = (metrics.widthPixels - dp(BALL_DP + BALL_MARGIN_DP)).coerceAtLeast(0)
            y = (metrics.heightPixels * DOCK_Y_FRACTION).roundToInt()
                .coerceIn(0, (metrics.heightPixels - dp(BALL_DP)).coerceAtLeast(0))
        }
        return try {
            windowManager.addView(view, windowParams)
            root = view
            params = windowParams
            runCatching { context.registerComponentCallbacks(configCallbacks) }
                .onFailure { Log.w(TAG, "could not register configuration callbacks", it) }
            dockToEdge()
            schedulePeek()
            true
        } catch (e: Throwable) {
            // OEMs can refuse even with the grant (e.g. the permission was just
            // revoked, or the window type is blocked). Never bubble this up as a
            // crash path for the service.
            Log.e(TAG, "could not add the overlay view", e)
            false
        }
    }

    fun hide() {
        // Cancel first: an idle peek must never fire against a removed window.
        idleHandler.removeCallbacks(peekRunnable)
        peeked = false
        runCatching { context.unregisterComponentCallbacks(configCallbacks) }
            .onFailure { Log.w(TAG, "could not unregister configuration callbacks", it) }
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
            .onFailure { Log.w(TAG, "could not remove the overlay view", it) }
        root = null
        panel = null
        ball = null
        tab = null
        status = null
        params = null
        expanded = false
    }

    /** Re-reads [statusLine] into the panel while it is open. */
    fun refresh() {
        if (expanded) status?.text = statusLine()
    }

    private fun buildView(): LinearLayout {
        val panelView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = pill(PANEL_BG)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val statusView = TextView(context).apply {
            textSize = 11f
            setTextColor(FG)
            maxLines = 3
            text = statusLine()
            setPadding(dp(2), 0, dp(2), dp(6))
        }
        status = statusView
        panelView.addView(statusView)
        panelView.addView(actionRow("打开 Podroid") { collapse(); onOpenApp() })
        panelView.addView(actionRow("启动 / 停止") { collapse(); onToggleVm() })
        panelView.addView(actionRow("重播全部转发") { collapse(); onRestartForwards() })
        panelView.addView(actionRow("复制状态") { copyStatus() })
        // The hint matters: hiding used to be a one-way door for the rest of the
        // session with no visible way back, so tell the user where the switch is.
        panelView.addView(actionRow("隐藏圆点") {
            collapse()
            Toast.makeText(
                context,
                "已隐藏悬浮球，可在「Podroid 设置 → 悬浮球」重新打开",
                Toast.LENGTH_LONG,
            ).show()
            onHide()
        })
        panel = panelView

        val ballView = TextView(context).apply {
            text = "●"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = circle(BALL_BG)
            layoutParams = LinearLayout.LayoutParams(dp(BALL_DP), dp(BALL_DP))
            isClickable = true
            setOnTouchListener(dragAndTap())
        }
        ball = ballView

        val tabView = TextView(context).apply {
            visibility = View.GONE
            // A slim rounded bar rather than a squashed circle: at 11dp wide the
            // ball's oval background would read as a stray speck, while a bar
            // looks like the edge of something that is docked there. Kept faint
            // (see TAB_BG) so it never pulls the eye away from the video/page.
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(5).toFloat()
                setColor(TAB_BG)
            }
            layoutParams = LinearLayout.LayoutParams(dp(PEEK_SLIVER_DP), dp(PEEK_SLIVER_HEIGHT_DP))
            isClickable = true
            setOnTouchListener(dragAndTap())
        }
        tab = tabView

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Panel first: docked on the right edge (the default) it opens
            // leftwards. orderPanelForDock() re-sorts these three when the ball
            // has been dragged to the left edge, and keeps the tab on the
            // outside so a peeking window (tab only, 14dp wide) still lines up
            // with the edge it is docked to.
            addView(panelView)
            addView(ballView, LinearLayout.LayoutParams(dp(BALL_DP), dp(BALL_DP)).apply {
                marginStart = dp(BALL_MARGIN_DP)
            })
            addView(tabView)
        }
    }

    private fun actionRow(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(FG)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = pill(ROW_BG)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragAndTap(): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        // Set when this gesture began by un-peeking, so the release that ends the
        // tap does not also open the panel: "tap the strip" means "show the ball".
        var justUnpeeked = false
        return View.OnTouchListener { _, event ->
            val p = params ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    idleHandler.removeCallbacks(peekRunnable)
                    justUnpeeked = unpeek()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    // Sampled after un-peeking: a drag that starts on the strip
                    // must not begin from the off-screen x, or the ball jumps on
                    // the first move.
                    startX = p.x
                    startY = p.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        p.x = startX + dx.roundToInt()
                        p.y = startY + dy.roundToInt()
                        root?.let { runCatching { windowManager.updateViewLayout(it, p) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // A tap (no movement past touchSlop) opens/closes the panel;
                    // a drag just re-docks; the first tap after peeking only
                    // brings the ball back.
                    if (dragging) {
                        snapToEdge()
                        schedulePeek()
                    } else if (justUnpeeked) {
                        justUnpeeked = false
                        schedulePeek()
                    } else {
                        togglePanel()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapToEdge()
                    justUnpeeked = false
                    schedulePeek()
                    true
                }
                else -> false
            }
        }
    }

    private fun togglePanel() {
        val panelView = panel ?: return
        expanded = !expanded
        if (expanded) {
            // Never peek while the panel is open; the user is clearly busy here.
            idleHandler.removeCallbacks(peekRunnable)
            status?.text = statusLine()
            orderPanelForDock()
        }
        panelView.visibility = if (expanded) View.VISIBLE else View.GONE
        // Opening widens the window and closing narrows it again, so the x that
        // was right a moment ago no longer is. Re-anchor rather than merely
        // clamping: clamping kept the expanded x after the panel closed, which
        // left the ball stranded near the middle of the screen instead of
        // hugging the edge it had been docked to.
        dockToEdge()
        if (!expanded) schedulePeek()
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        panel?.visibility = View.GONE
        dockToEdge()
        schedulePeek()
    }

    // ---- idle peeking ------------------------------------------------------

    /** (Re)arms the idle timer. Any touch cancels it first. */
    private fun schedulePeek() {
        idleHandler.removeCallbacks(peekRunnable)
        idleHandler.postDelayed(peekRunnable, AUTO_PEEK_DELAY_MS)
    }

    /**
     * Shrinks the window down to the [PEEK_SLIVER_DP] x [PEEK_SLIVER_HEIGHT_DP]
     * [tab] pinned at the docked edge.
     *
     * This used to slide the whole window mostly off-screen (x = screenWidth -
     * sliver). On at least one ROM that is a no-op: the window manager clamps an
     * overlay back inside the display, so the ball never moved. Here nothing
     * ever leaves the screen - the ball and panel go GONE, the tab becomes the
     * only child, and the wrap-content window measures 11dp. The tab is laid out
     * on the outside of the row, so x = screenWidth - 11dp puts it exactly in
     * the corner it was docked to.
     *
     * The vertical centre of the ball is kept: the tab is shorter than the ball,
     * so re-using y would leave it hanging from the ball's top edge. Both
     * `unpeek()` and this method therefore convert between the two heights
     * around a fixed centre instead of shifting y by a delta (a delta would
     * double-apply if the same path ran twice).
     */
    private fun peekToEdge() {
        val view = root ?: return
        if (expanded || peeked) return
        val ballView = ball ?: return
        val tabView = tab ?: return
        val p = params ?: return
        val metrics = context.resources.displayMetrics
        val sliver = dp(PEEK_SLIVER_DP)
        val tabHeight = dp(PEEK_SLIVER_HEIGHT_DP)
        val ballCenterY = p.y + dp(BALL_DP) / 2
        peeked = true
        panel?.visibility = View.GONE
        ballView.visibility = View.GONE
        tabView.visibility = View.VISIBLE
        p.x = if (dockedLeft) 0 else (metrics.widthPixels - sliver).coerceAtLeast(0)
        p.y = (ballCenterY - tabHeight / 2)
            .coerceIn(0, (metrics.heightPixels - tabHeight).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, p) }
            .onSuccess {
                Log.i(TAG, "peeked: docked=${if (dockedLeft) "left" else "right"} x=${p.x} y=${p.y}")
            }
            .onFailure { Log.w(TAG, "peek update failed", it) }
    }

    /** Brings the whole ball back. Returns true when it actually was peeking. */
    private fun unpeek(): Boolean {
        if (!peeked) return false
        peeked = false
        val tabView = tab
        val ballView = ball
        tabView?.visibility = View.GONE
        ballView?.visibility = View.VISIBLE
        val view = root
        val p = params
        if (view == null || p == null) return true
        val metrics = context.resources.displayMetrics
        // Use the width the window is about to have, not view.width: until the
        // next layout pass that still reports the 11dp tab, and a stale x here
        // would also poison the drag anchor sampled right after this returns.
        val dockWidth = dp(BALL_DP + BALL_MARGIN_DP)
        // Same centre-preserving conversion as peekToEdge(), in reverse: the tab
        // is shorter than the ball, so the ball must be pulled up by half the
        // difference or it would keep the tab's top edge.
        val tabCenterY = p.y + dp(PEEK_SLIVER_HEIGHT_DP) / 2
        p.x = if (dockedLeft) 0 else (metrics.widthPixels - dockWidth).coerceAtLeast(0)
        p.y = (tabCenterY - dp(BALL_DP) / 2)
            .coerceIn(0, (metrics.heightPixels - dp(BALL_DP)).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, p) }
            .onSuccess { Log.i(TAG, "unpeeked: x=${p.x}") }
            .onFailure { Log.w(TAG, "unpeek update failed", it) }
        // Re-arm here, not only in the ACTION_UP branch: restoring the ball swaps
        // which view owns the gesture, so the matching ACTION_UP may never be
        // delivered to us. Without this the ball could stay fully visible
        // forever after a tap on the strip.
        schedulePeek()
        return true
    }

    /**
     * Puts the panel on the side away from the docked edge so it never covers
     * the ball. Child order matters: the window grows to the right of its x, so
     * a right-docked ball has to be the last child, a left-docked one the first.
     */
    private fun orderPanelForDock() {
        val container = root ?: return
        val panelView = panel ?: return
        val ballView = ball ?: return
        val tabView = tab ?: return
        val current = (0 until container.childCount).map { container.getChildAt(it) }
        val desired = if (dockedLeft) {
            listOf(tabView, ballView, panelView)
        } else {
            listOf(panelView, ballView, tabView)
        }
        if (current == desired) return
        container.removeAllViews()
        if (dockedLeft) {
            container.addView(tabView)
            container.addView(ballView, ballLayoutParams(0))
            container.addView(panelView)
        } else {
            container.addView(panelView)
            container.addView(ballView, ballLayoutParams(dp(BALL_MARGIN_DP)))
            container.addView(tabView)
        }
    }

    private fun ballLayoutParams(marginStartPx: Int) =
        LinearLayout.LayoutParams(dp(BALL_DP), dp(BALL_DP)).apply {
            marginStart = marginStartPx
        }

    /**
     * Glues the window to the docked edge. Posting is required: the view has not
     * been re-measured when the panel's visibility flips, so the width used here
     * must be the one it is about to have, not the stale one.
     */
    private fun dockToEdge() {
        val view = root ?: return
        view.post {
            val p = params ?: return@post
            val metrics = context.resources.displayMetrics
            val width = if (view.width > 0) view.width else dp(BALL_DP + BALL_MARGIN_DP)
            p.x = if (dockedLeft) 0 else (metrics.widthPixels - width).coerceAtLeast(0)
            p.y = p.y.coerceIn(0, (metrics.heightPixels - view.height).coerceAtLeast(0))
            runCatching { windowManager.updateViewLayout(view, p) }
        }
    }

    /**
     * Re-pins the window after the screen rotated (or the display size
     * otherwise changed). Applied twice: immediately, and once more 300 ms later
     * in case a ROM reports the new configuration before the display metrics it
     * hands to this process have settled. Both passes are idempotent - they
     * recompute x from scratch.
     */
    private fun reDockAfterRotation() {
        val view = root ?: return
        view.post { applyDockedPosition() }
        view.postDelayed({ applyDockedPosition() }, 300L)
    }

    /**
     * Puts the window back on its docked edge using the *current* screen width.
     * Works for the expanded panel, the full ball and the peeking tab: each one
     * has a different width, so it is derived from the state rather than from
     * [View.getWidth], which still reports the pre-rotation value at this point.
     */
    private fun applyDockedPosition() {
        val view = root ?: return
        val p = params ?: return
        val metrics = context.resources.displayMetrics
        val width = when {
            peeked -> dp(PEEK_SLIVER_DP)
            view.width > 0 -> view.width
            else -> dp(BALL_DP + BALL_MARGIN_DP)
        }
        val height = when {
            peeked -> dp(PEEK_SLIVER_HEIGHT_DP)
            view.height > 0 -> view.height
            else -> dp(BALL_DP)
        }
        val oldX = p.x
        val oldY = p.y
        p.x = if (dockedLeft) 0 else (metrics.widthPixels - width).coerceAtLeast(0)
        p.y = p.y.coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, p) }
            .onSuccess {
                Log.i(
                    TAG,
                    "re-docked after rotation: docked=${if (dockedLeft) "left" else "right"} " +
                        "peeked=$peeked x=$oldX->${p.x} y=$oldY->${p.y} screen=${metrics.widthPixels}x${metrics.heightPixels}",
                )
            }
            .onFailure { Log.w(TAG, "re-dock update failed", it) }
    }

    private fun snapToEdge() {
        val view = root ?: return
        view.post {
            val p = params ?: return@post
            val metrics = context.resources.displayMetrics
            val width = if (view.width > 0) view.width else dp(BALL_DP + BALL_MARGIN_DP)
            dockedLeft = p.x + width / 2 < metrics.widthPixels / 2
            p.x = if (dockedLeft) 0 else (metrics.widthPixels - width).coerceAtLeast(0)
            p.y = p.y.coerceIn(0, (metrics.heightPixels - view.height).coerceAtLeast(0))
            runCatching { windowManager.updateViewLayout(view, p) }
        }
    }

    private fun copyStatus() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Podroid", statusLine()))
            Toast.makeText(context, "状态已复制", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.w(TAG, "clipboard write failed", e)
        }
        collapse()
    }

    private fun pill(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(color)
    }

    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
