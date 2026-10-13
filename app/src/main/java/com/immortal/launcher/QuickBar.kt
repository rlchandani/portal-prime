/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The quick-button cluster: a centered row of buttons pinned to the top of the screen, over any
 * app. Centered (not left/right) so it never collides with the system bar's back/home (left) or
 * status icons (right), and works on any Portal size without per-model layout.
 *
 * Hosted by [BarWatchService] as a **TYPE_ACCESSIBILITY_OVERLAY** — that renders ABOVE the system
 * bar (a plain app overlay sits below it, so it would draw under the bar and its taps would be
 * eaten). The same service detects the bar reveal and drives [setBarVisible]. Visibility =
 * always-show OR the top bar is showing. The accessibility service only runs while the feature is
 * enabled (immortal self-enables it), so no separate foreground service is needed.
 *
 * v1 holds one button (app switcher). The row is the extension point — add child views later.
 */
object QuickBar {
  private const val TAG = "ImmortalQuickBar"
  private val main = Handler(Looper.getMainLooper())

  private var host: AccessibilityService? = null
  private var wm: WindowManager? = null
  private var view: View? = null
  @Volatile private var barVisible = false
  private var switcher: View? = null
  private var pinButton: ImageView? = null
  private var pinAvailable = false
  private var pinned = false
  private var frameCovered = false
  private val refreshLock = Any()
  private var refreshQueued = false
  private var refreshRequired = false
  private val refreshRunnable = Runnable {
    synchronized(refreshLock) {
      if (!refreshQueued) return@Runnable
      refreshQueued = false
      refreshRequired = false
    }
    refresh()
  }

  /** Main-thread diagnostics for our own control; never inspect another app's nodes/content. */
  fun pinControlStatus(): JSONObject {
    val button = pinButton
    val bounds = Rect()
    val visible = button?.getGlobalVisibleRect(bounds) == true && view?.isShown == true
    if (visible) {
      val origin = IntArray(2)
      view?.getLocationOnScreen(origin)
      bounds.offset(origin[0], origin[1]) // getGlobalVisibleRect is relative to this window's root
    } else bounds.setEmpty()
    val location = IntArray(2)
    button?.getLocationOnScreen(location)
    val dm = host?.resources?.displayMetrics
    return JSONObject().put("barVisible", barVisible).put("available", pinAvailable)
        .put("selected", pinned).put("frameCovered", frameCovered)
        .put("attached", view?.isAttachedToWindow == true).put("visible", visible)
        .put("left", bounds.left).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom)
        .put("displayWidth", dm?.widthPixels ?: 0).put("displayHeight", dm?.heightPixels ?: 0)
        .put("density", dm?.density ?: 0f)
        .put("buttonX", location[0]).put("buttonY", location[1])
        .put("buttonWidth", button?.width ?: 0).put("buttonHeight", button?.height ?: 0)
  }

  fun setPinState(available: Boolean, selected: Boolean, covered: Boolean) {
    synchronized(refreshLock) {
      val changed = pinAvailable != available || pinned != selected || frameCovered != covered
      pinAvailable = available; pinned = selected; frameCovered = covered
      if (changed || refreshRequired) {
        refreshRequired = true
        queueRefreshLocked()
      }
    }
  }

  /** Add the (initially evaluated) cluster overlay, hosted by the accessibility service. */
  fun attach(service: AccessibilityService) {
    main.post {
      if (view != null) {
        refresh()
        return@post
      }
      host = service
      val wmgr = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
      wm = wmgr
      val cluster = buildCluster(service)
      val lp =
          WindowManager.LayoutParams(
                  WindowManager.LayoutParams.WRAP_CONTENT,
                  // Span the full bar height so the button can sit vertically centered within it,
                  // matching the back/home buttons (rather than flush to the top edge).
                  statusBarHeight(service),
                  WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                  PixelFormat.TRANSLUCENT,
              )
              .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
      runCatching { wmgr.addView(cluster, lp); view = cluster }
          .onFailure { Log.w(TAG, "addView failed", it) }
      refresh()
    }
  }

  fun detach() {
    synchronized(refreshLock) {
      refreshQueued = false
      refreshRequired = false
      main.removeCallbacks(refreshRunnable)
      main.post {
        view?.let { v -> runCatching { wm?.removeView(v) } }
        view = null
        switcher = null; pinButton = null
        synchronized(refreshLock) {
          pinAvailable = false; pinned = false; frameCovered = false
        }
        host = null
        wm = null
      }
    }
  }

  /** Raw system-bar state; independent of switcher configuration and pin eligibility. */
  internal fun isBarVisible(): Boolean = barVisible

  /** Called by [BarWatchService] when the system top bar is revealed / hidden. */
  fun setBarVisible(visible: Boolean) {
    synchronized(refreshLock) {
      val changed = barVisible != visible
      barVisible = visible
      if (changed || refreshRequired) {
        refreshRequired = true
        queueRefreshLocked()
      }
    }
  }

  /** Re-evaluate visibility after a config change (e.g. the always-show toggle). */
  fun applyConfig(): Boolean = synchronized(refreshLock) {
    refreshRequired = true
    queueRefreshLocked()
  }

  /** Called under refreshLock. Failed posting keeps the desired state retryable. */
  private fun queueRefreshLocked(): Boolean {
    if (refreshQueued) return true
    refreshQueued = true
    return main.post(refreshRunnable).also { if (!it) refreshQueued = false }
  }

  private fun refresh() {
    val v = view ?: return
    val ctx = host ?: return
    // BarWatchService is baseline-enabled now (it also backs the remote + the Calls→stock-home
    // bridge), so the service being connected no longer implies the quick-button feature is on.
    // Gate the cluster on its own setting; show only when enabled AND (always-show or bar shown).
    val showSwitcher = QuickBarConfig.isEnabled(ctx) && (QuickBarConfig.alwaysShow(ctx) || barVisible)
    val showPin = (pinAvailable || pinned) && barVisible && !frameCovered
    switcher?.visibility = if (showSwitcher) View.VISIBLE else View.GONE
    pinButton?.apply {
      visibility = if (showPin) View.VISIBLE else View.GONE
      contentDescription = if (pinned) "Unpin app" else "Pin app for screensaver return"
      tooltipText = contentDescription
      setColorFilter(if (pinned) 0xFF83BAFF.toInt() else 0xFFFFFFFF.toInt())
    }
    v.visibility = if (!frameCovered && (showSwitcher || showPin)) View.VISIBLE else View.GONE
  }

  private fun statusBarHeight(ctx: Context): Int {
    val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) ctx.resources.getDimensionPixelSize(id)
    else (28 * ctx.resources.displayMetrics.density).toInt()
  }

  // --- UI ---------------------------------------------------------------------

  private fun buildCluster(ctx: Context): View =
      // Fills the bar-height window; CENTER keeps the button vertically centered in the bar.
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(appSwitcherButton(ctx).also { switcher = it })
        addView(appSwitcherButton(ctx).let { template ->
          (template as ImageView).apply {
            setImageResource(R.drawable.ic_pin_app)
            setOnClickListener {
              if (PinnedAppScreensaver.hasPin()) {
                PinnedAppScreensaver.release()
                android.widget.Toast.makeText(ctx, "App unpinned", android.widget.Toast.LENGTH_SHORT).show()
              } else if (!PinnedAppScreensaver.pinCurrent()) {
                android.widget.Toast.makeText(ctx, "Open an app first, then pin it here", android.widget.Toast.LENGTH_SHORT).show()
              }
              PinnedAppScreensaver.windowsChanged()
            }
          }
        }.also { pinButton = it })
      }

  /** A pill sized/shaped to match the system bar's back/home buttons, with the standard recents glyph. */
  private fun appSwitcherButton(ctx: Context): View {
    val d = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    return ImageView(ctx).apply {
      setImageResource(R.drawable.ic_app_switcher)
      scaleType = ImageView.ScaleType.CENTER_INSIDE
      // Match the back/home pill footprint: wider than tall, fully rounded, light fill.
      minimumWidth = dp(76)
      minimumHeight = dp(44)
      setPadding(dp(20), dp(10), dp(20), dp(10))
      background =
          GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setColor(0x33FFFFFF)
          }
      setOnClickListener {
        runCatching {
          ctx.startActivity(
              Intent(ctx, AppSwitcherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
      }
    }
  }
}
