/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.TimeZone

/**
 * Portal-wide Control Center banners, generalised from the doorbell card: the same
 * `TYPE_ACCESSIBILITY_OVERLAY` host ([BarWatchService]) so they show over Home, any app or the
 * photo frame. [CcBannerPolicy] decides: critical alerts take over the screen on every Portal
 * (kids mode and banners-only too); warnings/info are a top banner on Full Portals. Sound obeys
 * the household quiet hours (Settings › Sounds, 22:00–08:00 by default). Main thread only.
 */
internal object ControlCenterBanners {
  private const val TAG = "HearthpaneBanner"
  private const val BANNER_MS = 20_000L
  private const val TAKEOVER_MS = 10 * 60_000L
  private const val CRITICAL_RINGS = 3
  private const val PERCENT = 100f
  private const val MIN_VOLUME = 0.5f
  private const val CRITICAL_BG = 0xFF43191E.toInt()
  private const val CRITICAL_ACCENT = 0xFFFF747D.toInt()
  private const val WARN_ACCENT = 0xFFF0A65A.toInt()
  private const val PREVIEW_TRACKING = 0.12f

  private val main = Handler(Looper.getMainLooper())
  private var host: AccessibilityService? = null
  private var wm: WindowManager? = null
  private var view: View? = null
  /** The window manager [view] was added to (the accessibility host's, or a preview's Control Center window). */
  private var viewWm: WindowManager? = null
  private var viewHost: CcBannerHost = CcBannerHost.NONE
  private var showing: CcBannerSlot.Showing? = null
  private val timeout = Runnable { remove() }

  fun attach(service: AccessibilityService) = main.post { host = service; wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager }

  fun detach() = main.post { if (CcBannerHost.removedOnDetach(viewHost)) remove(); host = null; wm = null }

  /** From the engine thread when an alert first becomes active. */
  fun offer(context: Context, alert: CcAlert, settings: ControlCenterConfig.Settings, appVisible: Boolean, dryRun: Boolean = false) {
    val app = context.applicationContext
    val chime = runCatching { ChimeConfig.load(app) }.getOrNull()
    val quiet = quietNow(chime)
    val decision = CcBannerPolicy.decide(alert, settings, quiet)
    if (dryRun) {
      // Dry run: the decision is logged; nothing is shown and no sound plays.
      Log.i(TAG, "dry-run banner ${alert.severity.name.lowercase()} class=${alert.deviceClass ?: "-"} quiet=$quiet " +
          "show=${decision.show.name.lowercase()} sound=${decision.sound}")
      return
    }
    if (decision.show == CcBannerPolicy.Show.NONE) return
    // The Control Center already lists warnings and info while it's on screen; only criticals cover it.
    if (appVisible && decision.show != CcBannerPolicy.Show.TAKEOVER) return
    Log.i(TAG, "control center ${alert.severity.name.lowercase()} alert shown")
    main.post {
      if (!CcBannerSlot.admitReal(showing, alert)) return@post
      val ctx = host ?: run { Log.w(TAG, "banner before the accessibility host attached; not shown"); return@post }
      val wmgr = wm ?: return@post
      ScreenControl.wake(app)
      val takeover = decision.show == CcBannerPolicy.Show.TAKEOVER
      show(ctx, wmgr, alert, takeover, if (takeover) TAKEOVER_MS else BANNER_MS, preview = false,
          CcBannerHost.ACCESSIBILITY)
      if (decision.sound) {
        val volume = ((chime?.volume ?: 0) / PERCENT).coerceIn(MIN_VOLUME, 1f)
        runCatching { ChimePlayer.playPing(app, CRITICAL_RINGS, volume) }.onFailure { Log.w(TAG, "alert sound failed", it) }
      }
    }
  }

  /**
   * A preview from the Alerts screen ([CcBannerPreview]): the real banner or takeover look, tagged
   * "Preview", gone after [CcBannerPreview.TIMEOUT_MS]. It never sounds, wakes, relays or touches the
   * alert gate, and never replaces a real alert. Not a sample? Ignored. Drawn in [activity]'s own
   * window (see [CcBannerHost]); the accessibility overlay only when there is no usable activity.
   */
  fun preview(activity: Activity?, alert: CcAlert) {
    val req = CcBannerPreview.request(alert) ?: return
    main.post {
      val sev = req.alert.severity.name.lowercase()
      if (!CcBannerSlot.admitPreview(showing)) { Log.i(TAG, "banner preview $sev skipped: a real alert is showing"); return@post }
      val a11y = host
      val a11yWm = wm
      val usable = activity?.takeUnless { it.isFinishing || it.isDestroyed }
      val where = CcBannerHost.forPreview(a11y != null && a11yWm != null, usable != null)
      Log.i(TAG, "banner preview $sev host=${where.name.lowercase()}")
      when {
        where == CcBannerHost.ACCESSIBILITY && a11y != null && a11yWm != null -> show(a11y, a11yWm, req.alert, req.takeover, req.timeoutMs,
            preview = true, CcBannerHost.ACCESSIBILITY)
        where == CcBannerHost.ACTIVITY && usable != null -> show(usable, usable.windowManager, req.alert, req.takeover, req.timeoutMs,
            preview = true, CcBannerHost.ACTIVITY, usable.window?.decorView?.windowToken)
        else -> Log.w(TAG, "banner preview $sev: no window to draw in; not shown")
      }
    }
  }

  /** [activity] is leaving the screen: a preview drawn in its window goes with it. */
  fun releaseActivity(activity: Activity) = main.post { if (viewWm === activity.windowManager) remove() }

  /** Household quiet hours now (Settings › Sounds); unreadable settings count as quiet, so nothing sounds by mistake. */
  fun quietNow(context: Context): Boolean = quietNow(runCatching { ChimeConfig.load(context.applicationContext) }.getOrNull())

  private fun quietNow(chime: ChimeConfig.Settings?): Boolean =
      chime?.let { DinnerQuiet.isQuiet(it, System.currentTimeMillis(), TimeZone.getDefault()) } ?: true

  private fun show(ctx: Context, wmgr: WindowManager, alert: CcAlert, takeover: Boolean, timeoutMs: Long, preview: Boolean,
                   where: CcBannerHost, token: android.os.IBinder? = null) {
    val type = if (where == CcBannerHost.ACTIVITY) WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
        else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    remove()
    val root = if (takeover) takeoverView(ctx, alert, preview) else bannerView(ctx, alert, preview)
    val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
        if (takeover) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        .apply { gravity = Gravity.TOP; this.token = token }
    runCatching { wmgr.addView(root, lp) }.onFailure { Log.w(TAG, "addView failed (preview=$preview)", it); return }
    view = root; viewWm = wmgr; viewHost = where; showing = CcBannerSlot.Showing(alert.severity, preview)
    main.postDelayed(timeout, timeoutMs)
  }

  fun hide() = main.post { remove() }

  private fun remove() {
    main.removeCallbacks(timeout)
    view?.let { v -> runCatching { viewWm?.removeView(v) }.onFailure { Log.w(TAG, "removeView failed", it) } }
    view = null
    viewWm = null
    viewHost = CcBannerHost.NONE
    showing = null
  }

  private fun px(ctx: Context, dp: Int) = (dp * ctx.resources.displayMetrics.density).toInt()

  private fun bannerView(ctx: Context, alert: CcAlert, preview: Boolean): View {
    val frame = FrameLayout(ctx).apply { setPadding(px(ctx, 24), px(ctx, 20), px(ctx, 24), 0) }
    val row = LinearLayout(ctx).apply {
      orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
      background = rounded(DinnerCardOverlay.CARD, px(ctx, 32).toFloat()); elevation = px(ctx, 12).toFloat()
      setPadding(px(ctx, 28), px(ctx, 22), px(ctx, 22), px(ctx, 22)); isClickable = true
      contentDescription = "${alert.title}. ${alert.body}"
    }
    row.addView(glyph(ctx, alert, 88, if (alert.severity == CcSeverity.WARNING) WARN_ACCENT else Color.WHITE),
        LinearLayout.LayoutParams(px(ctx, 88), px(ctx, 88)).apply { rightMargin = px(ctx, 24) })
    row.addView(texts(ctx, alert, 30f, 22f, preview), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    if (!preview) row.addView(button(ctx, "View", false) { remove(); ControlCenterActivity.open(ctx) },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(ctx, 80)).apply { rightMargin = px(ctx, 14) })
    row.addView(button(ctx, if (preview) CcBannerPreview.CLOSE_LABEL else "Dismiss", true) { remove() },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(ctx, 80)))
    frame.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    return frame
  }

  private fun takeoverView(ctx: Context, alert: CcAlert, preview: Boolean): View {
    val root = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(CRITICAL_BG); isClickable = true
      setPadding(px(ctx, 80), px(ctx, 60), px(ctx, 80), px(ctx, 60)); contentDescription = "${alert.title}. ${alert.body}"
    }
    if (preview) root.addView(previewTag(ctx, 26f), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(ctx, 28) })
    root.addView(glyph(ctx, alert, 180, CRITICAL_ACCENT), LinearLayout.LayoutParams(px(ctx, 180), px(ctx, 180)).apply { bottomMargin = px(ctx, 36) })
    root.addView(TextView(ctx).apply { text = alert.title; setTextColor(Color.WHITE); textSize = 64f; typeface = Typeface.SERIF; gravity = Gravity.CENTER })
    root.addView(TextView(ctx).apply { text = alert.body; setTextColor(DinnerCardOverlay.SECONDARY); textSize = 30f; gravity = Gravity.CENTER
      setPadding(0, px(ctx, 16), 0, px(ctx, 44)) })
    val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    if (!preview) actions.addView(button(ctx, "Open Control Center", false) { remove(); ControlCenterActivity.open(ctx) },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(ctx, 104)).apply { rightMargin = px(ctx, 24) })
    actions.addView(button(ctx, if (preview) CcBannerPreview.CLOSE_LABEL else "I'm on it", true) { remove() },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(ctx, 104)))
    root.addView(actions)
    return root
  }

  private fun glyph(ctx: Context, alert: CcAlert, sizeDp: Int, tint: Int): View = FrameLayout(ctx).apply {
    background = GradientDrawable().apply { setColor(DinnerCardOverlay.TONAL); cornerRadius = px(ctx, sizeDp / 4).toFloat() }
    addView(ImageView(ctx).apply { setImageResource(icon(alert)); setColorFilter(tint) },
        FrameLayout.LayoutParams(px(ctx, sizeDp / 2), px(ctx, sizeDp / 2), Gravity.CENTER))
  }

  private fun icon(alert: CcAlert): Int = when (alert.kind) {
    CcKind.GARAGE, CcKind.DOOR -> DinnerIcons.resource("door")
    CcKind.BATTERY -> R.drawable.approved_icon_battery
    CcKind.LEAK, CcKind.SMOKE -> R.drawable.approved_icon_bell
    else -> R.drawable.approved_icon_info
  }

  private fun texts(ctx: Context, alert: CcAlert, titleSp: Float, lineSp: Float, preview: Boolean): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    if (preview) addView(previewTag(ctx, 18f), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(ctx, 8) })
    addView(TextView(ctx).apply { text = alert.title; setTextColor(Color.WHITE); textSize = titleSp; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); maxLines = 1 })
    addView(TextView(ctx).apply { text = alert.body; setTextColor(DinnerCardOverlay.SECONDARY); textSize = lineSp; maxLines = 2 })
  }

  private fun button(ctx: Context, label: String, solid: Boolean, onClick: () -> Unit): View = TextView(ctx).apply {
    text = label; gravity = Gravity.CENTER; textSize = 26f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    setPadding(px(ctx, 40), 0, px(ctx, 40), 0)
    setTextColor(if (solid) Color.BLACK else Color.WHITE)
    background = rounded(if (solid) Color.WHITE else DinnerCardOverlay.TONAL, px(ctx, 52).toFloat())
    isClickable = true; isFocusable = true; contentDescription = label
    setOnClickListener { onClick() }
  }

  /** The amber "PREVIEW" pill on every preview, so it can't be read as a real alert. */
  private fun previewTag(ctx: Context, sp: Float): View = TextView(ctx).apply {
    text = CcBannerPreview.TAG_LABEL.uppercase(); textSize = sp; letterSpacing = PREVIEW_TRACKING
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); setTextColor(Color.BLACK)
    setPadding(px(ctx, 16), px(ctx, 4), px(ctx, 16), px(ctx, 4)); background = rounded(WARN_ACCENT, px(ctx, 20).toFloat())
  }

  private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
}
