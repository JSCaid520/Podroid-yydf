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
 * Drag it anywhere; on release it snaps to the nearer screen edge.
 */
package com.excp.podroid.service

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
 * @param onHide hide the bubble for the rest of this VM session.
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

        const val ROW_BG = 0x33FFFFFF

        // Not `const`: an `.toInt()` conversion is not a compile-time constant in
        // Kotlin, and these ARGB literals do not fit a positive Int.
        /** Panel background (near-black, ~90% opaque). */
        val PANEL_BG = 0xE6202124.toInt()

        /** Ball background (Podroid blue, ~80% opaque). */
        val BALL_BG = 0xCC2F6FED.toInt()

        val FG = 0xFFE6E1E5.toInt()
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var params: WindowManager.LayoutParams? = null
    private var root: LinearLayout? = null
    private var panel: LinearLayout? = null
    private var status: TextView? = null
    private var expanded = false

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
            x = (metrics.widthPixels - dp(BALL_DP + 10)).coerceAtLeast(0)
            y = (metrics.heightPixels / 2).coerceAtLeast(0)
        }
        return try {
            windowManager.addView(view, windowParams)
            root = view
            params = windowParams
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
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
            .onFailure { Log.w(TAG, "could not remove the overlay view", it) }
        root = null
        panel = null
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
        panelView.addView(actionRow("隐藏圆点") { collapse(); onHide() })
        panel = panelView

        val ball = TextView(context).apply {
            text = "●"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = circle(BALL_BG)
            layoutParams = LinearLayout.LayoutParams(dp(BALL_DP), dp(BALL_DP))
            isClickable = true
            setOnTouchListener(dragAndTap())
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Panel first: docked on the right edge (the default) it opens leftwards.
            addView(panelView)
            addView(ball, LinearLayout.LayoutParams(dp(BALL_DP), dp(BALL_DP)).apply {
                marginStart = dp(6)
            })
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
        return View.OnTouchListener { _, event ->
            val p = params ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
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
                    // anything longer is a drag and just re-docks.
                    if (dragging) snapToEdge() else togglePanel()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapToEdge()
                    true
                }
                else -> false
            }
        }
    }

    private fun togglePanel() {
        val panelView = panel ?: return
        expanded = !expanded
        if (expanded) status?.text = statusLine()
        panelView.visibility = if (expanded) View.VISIBLE else View.GONE
        clampToScreen()
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        panel?.visibility = View.GONE
        clampToScreen()
    }

    /**
     * Bring the window back inside the screen after a size change (expanding the
     * panel makes it wider/taller, which can push it off-screen when docked).
     * Posting is required: layout has not run yet when visibility flips.
     */
    private fun clampToScreen() {
        val view = root ?: return
        view.post {
            val p = params ?: return@post
            val metrics = context.resources.displayMetrics
            p.x = p.x.coerceIn(0, (metrics.widthPixels - view.width).coerceAtLeast(0))
            p.y = p.y.coerceIn(0, (metrics.heightPixels - view.height).coerceAtLeast(0))
            runCatching { windowManager.updateViewLayout(view, p) }
        }
    }

    private fun snapToEdge() {
        val view = root ?: return
        view.post {
            val p = params ?: return@post
            val metrics = context.resources.displayMetrics
            val maxX = (metrics.widthPixels - view.width).coerceAtLeast(0)
            p.x = if (p.x + view.width / 2 < metrics.widthPixels / 2) 0 else maxX
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
