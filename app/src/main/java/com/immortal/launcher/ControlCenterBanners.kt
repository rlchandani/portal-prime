/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
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
  private const val PREVIEW_MS = 8_000L
  private const val CRITICAL_RINGS = 3
  private const val PERCENT = 100f
  private const val MIN_VOLUME = 0.5f
  private const val CRITICAL_BG = 0xFF43191E.toInt()
  private const val CRITICAL_ACCENT = 0xFFFF747D.toInt()
  private const val WARN_ACCENT = 0xFFF0A65A.toInt()

  private val main = Handler(Looper.getMainLooper())
  private var host: AccessibilityService? = null
  private var wm: WindowManager? = null
  private var view: View? = null
  private var showingSeverity: CcSeverity? = null
  private val timeout = Runnable { remove() }

  fun attach(service: AccessibilityService) = main.post { host = service; wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager }

  fun detach() = main.post { remove(); host = null; wm = null }

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
      if (showingSeverity == CcSeverity.CRITICAL && alert.severity != CcSeverity.CRITICAL) return@post
      ScreenControl.wake(app)
      show(alert, decision.show == CcBannerPolicy.Show.TAKEOVER)
      if (decision.sound) {
        val volume = ((chime?.volume ?: 0) / PERCENT).coerceIn(MIN_VOLUME, 1f)
        runCatching { ChimePlayer.playPing(app, CRITICAL_RINGS, volume) }.onFailure { Log.w(TAG, "alert sound failed", it) }
      }
    }
  }

  /**
   * A preview from the Alerts screen ([CcBannerPreview]): drawn exactly as the real banner or
   * takeover, silently, without waking the screen, and gone after [PREVIEW_MS]. Not a sample? Ignored.
   */
  fun preview(alert: CcAlert) {
    if (!CcBannerPreview.isPreview(alert)) return
    main.post {
      Log.i(TAG, "banner preview ${alert.severity.name.lowercase()}")
      show(alert, CcBannerPreview.takeover(alert), PREVIEW_MS)
    }
  }

  /** Household quiet hours now (Settings › Sounds); unreadable settings count as quiet, so nothing sounds by mistake. */
  fun quietNow(context: Context): Boolean = quietNow(runCatching { ChimeConfig.load(context.applicationContext) }.getOrNull())

  private fun quietNow(chime: ChimeConfig.Settings?): Boolean =
      chime?.let { DinnerQuiet.isQuiet(it, System.currentTimeMillis(), TimeZone.getDefault()) } ?: true

  private fun show(alert: CcAlert, takeover: Boolean, timeoutMs: Long = if (takeover) TAKEOVER_MS else BANNER_MS) {
    val ctx = host ?: run { Log.w(TAG, "banner before the accessibility host attached; not shown"); return }
    val wmgr = wm ?: return
    remove()
    val root = if (takeover) takeoverView(ctx, alert) else bannerView(ctx, alert)
    val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
        if (takeover) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        .apply { gravity = Gravity.TOP }
    runCatching { wmgr.addView(root, lp); view = root; showingSeverity = alert.severity }.onFailure { Log.w(TAG, "addView failed", it); return }
    main.postDelayed(timeout, timeoutMs)
  }

  fun hide() = main.post { remove() }

  private fun remove() {
    main.removeCallbacks(timeout)
    view?.let { v -> runCatching { wm?.removeView(v) } }
    view = null
    showingSeverity = null
  }

  private fun px(ctx: Context, dp: Int) = (dp * ctx.resources.displayMetrics.density).toInt()

  private fun bannerView(ctx: Context, alert: CcAlert): View {
    val frame = FrameLayout(ctx).apply { setPadding(px(ctx, 24), px(ctx, 20), px(ctx, 24), 0) }
    val row = LinearLayout(ctx).apply {
      orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
      background = rounded(DinnerCardOverlay.CARD, px(ctx, 32).toFloat()); elevation = px(ctx, 12).toFloat()
      setPadding(px(ctx, 28), px(ctx, 22), px(ctx, 22), px(ctx, 22)); isClickable = true
      contentDescription = "${alert.title}. ${alert.body}"
    }
    row.addView(glyph(ctx, alert, 88, if (alert.severity == CcSeverity.WARNING) WARN_ACCENT else Color.WHITE),
        LinearLayout.LayoutParams(px(ctx, 88), px(ctx, 88)).apply { rightMargin = px(ctx, 24) })
    row.addView(texts(ctx, alert, 30f, 22f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(button(ctx, "View", false) { remove(); ControlCenterActivity.open(ctx) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(ctx, 80)).apply { rightMargin = px(ctx, 14) })
    row.addView(button(ctx, "Dismiss", true) { remove() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(ctx, 80)))
    frame.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    return frame
  }

  private fun takeoverView(ctx: Context, alert: CcAlert): View {
    val root = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(CRITICAL_BG); isClickable = true
      setPadding(px(ctx, 80), px(ctx, 60), px(ctx, 80), px(ctx, 60)); contentDescription = "${alert.title}. ${alert.body}"
    }
    root.addView(glyph(ctx, alert, 180, CRITICAL_ACCENT), LinearLayout.LayoutParams(px(ctx, 180), px(ctx, 180)).apply { bottomMargin = px(ctx, 36) })
    root.addView(TextView(ctx).apply { text = alert.title; setTextColor(Color.WHITE); textSize = 64f; typeface = Typeface.SERIF; gravity = Gravity.CENTER })
    root.addView(TextView(ctx).apply { text = alert.body; setTextColor(DinnerCardOverlay.SECONDARY); textSize = 30f; gravity = Gravity.CENTER
      setPadding(0, px(ctx, 16), 0, px(ctx, 44)) })
    val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    actions.addView(button(ctx, "Open Control Center", false) { remove(); ControlCenterActivity.open(ctx) },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(ctx, 104)).apply { rightMargin = px(ctx, 24) })
    actions.addView(button(ctx, "I'm on it", true) { remove() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(ctx, 104)))
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

  private fun texts(ctx: Context, alert: CcAlert, titleSp: Float, lineSp: Float): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
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

  private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
}
