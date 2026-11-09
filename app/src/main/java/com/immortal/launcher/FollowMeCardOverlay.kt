/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Follow-me's two surfaces, drawn like [DinnerCardOverlay] (a `TYPE_ACCESSIBILITY_OVERLAY` hosted
 * by [BarWatchService]) so they show over Home, an app or the photo-frame dream:
 * - the Ask card: large, centred, "Continue <title> here?" with Not now / Continue; closes after
 *   [ASK_MS] or a tap outside (both count as Not now);
 * - the Automatic banner: a top strip "Music followed you from <room>" with Undo for [FOLLOWED_MS],
 *   touch-through outside it so the music screen below stays usable.
 * One at a time (replace, don't stack). Main thread only. While the music screen is open both are
 * drawn inside it instead (see [MusicV3FollowMeInScreen]), so nothing covers the YouTube player.
 */
internal object FollowMeCardOverlay {
  private const val TAG = "HearthpaneFollowMe"
  const val ASK_MS = 30_000L
  const val FOLLOWED_MS = 15_000L
  private const val CARD_RADIUS_DP = 36
  private const val BUTTON_HEIGHT_DP = 104
  private const val GLYPH_DP = 200
  private const val TITLE_SP = 48f
  private const val LINE_SP = 30f
  private const val BUTTON_SP = 32f
  private const val BANNER_SP = 30f
  private const val BANNER_TOP_DP = 32
  private val main = Handler(Looper.getMainLooper())
  private var host: AccessibilityService? = null
  private var wm: WindowManager? = null
  private var view: View? = null
  private var onTimeout: (() -> Unit)? = null
  private val timeoutRunnable = Runnable {
    val timeout = onTimeout
    hide()
    timeout?.invoke()
  }

  data class Ask(val title: String, val line: String)

  fun attach(service: AccessibilityService) {
    main.post { host = service; wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager }
  }

  fun detach() {
    main.post { hide(); host = null; wm = null }
  }

  /**
   * Returns false when the accessibility host isn't attached (nothing shown). While the music screen
   * is open the ask is shown inside it instead ([MusicV3FollowMeInScreen]): this overlay would cover
   * the YouTube player.
   */
  fun showAsk(ask: Ask, onContinue: () -> Unit, onNotNow: () -> Unit): Boolean {
    if (MusicV3FollowMeInScreen.offerAsk(ask.title, ask.line, ASK_MS, onContinue, onNotNow)) return true.also { hide() }
    val ctx = host ?: return false.also { Log.w(TAG, "ask card before the accessibility host attached; not shown") }
    val root = askRoot(ctx, ask, onContinue = { hide(); onContinue() }, onNotNow = { hide(); onNotNow() })
    val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
    return add(root, lp, ASK_MS, onNotNow)
  }

  fun showFollowed(text: String, onUndo: () -> Unit): Boolean {
    if (MusicV3FollowMeInScreen.offerNotice(text, FOLLOWED_MS, onUndo)) return true.also { hide() }
    val ctx = host ?: return false.also { Log.w(TAG, "followed banner before the accessibility host attached; not shown") }
    val root = bannerRoot(ctx, text) { hide(); onUndo() }
    val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
        .apply { gravity = Gravity.TOP }
    return add(root, lp, FOLLOWED_MS, null)
  }

  fun hide() {
    main.removeCallbacks(timeoutRunnable)
    onTimeout = null
    view?.let { v -> runCatching { wm?.removeView(v) } }
    view = null
  }

  private fun add(root: View, lp: WindowManager.LayoutParams, showMs: Long, timeout: (() -> Unit)?): Boolean {
    val wmgr = wm ?: return false
    hide()
    runCatching { wmgr.addView(root, lp); view = root }.onFailure { Log.w(TAG, "addView failed", it); return false }
    onTimeout = timeout
    main.postDelayed(timeoutRunnable, showMs)
    return true
  }

  private fun askRoot(ctx: Context, ask: Ask, onContinue: () -> Unit, onNotNow: () -> Unit): View {
    val px = pxOf(ctx)
    val scrim = FrameLayout(ctx).apply { setBackgroundColor(DinnerCardOverlay.SCRIM); setOnClickListener { onNotNow() } }
    val card = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      background = rounded(DinnerCardOverlay.CARD, px(CARD_RADIUS_DP).toFloat())
      clipToOutline = true
      isClickable = true // taps on the card don't fall through to the scrim
      contentDescription = ask.title
    }
    card.addView(LinearLayout(ctx).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(px(56), px(90), px(56), px(60))
      addView(glyph(ctx, px), LinearLayout.LayoutParams(px(GLYPH_DP), px(GLYPH_DP)).apply { rightMargin = px(56) })
      addView(LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(ctx, ask.title, TITLE_SP, Color.WHITE, bold = true, lines = 2))
        addView(text(ctx, ask.line, LINE_SP, DinnerCardOverlay.SECONDARY, bold = false, lines = 1)
            .apply { setPadding(0, px(14), 0, 0) })
      }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    })
    card.addView(LinearLayout(ctx).apply {
      gravity = Gravity.END or Gravity.CENTER_VERTICAL
      setPadding(px(56), px(10), px(56), px(56))
      addView(button(ctx, FollowMeCopy.NOT_NOW, solid = false, px, onNotNow),
          LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(BUTTON_HEIGHT_DP)).apply { rightMargin = px(20) })
      addView(button(ctx, FollowMeCopy.CONTINUE, solid = true, px, onContinue),
          LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(BUTTON_HEIGHT_DP)))
    })
    card.addView(drainBar(ctx, px, ASK_MS))
    scrim.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER).apply { setMargins(px(24), 0, px(24), 0) })
    return scrim
  }

  private fun bannerRoot(ctx: Context, message: String, onUndo: () -> Unit): View {
    val px = pxOf(ctx)
    val card = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      background = rounded(DinnerCardOverlay.CARD, px(CARD_RADIUS_DP).toFloat())
      clipToOutline = true
      isClickable = true
      contentDescription = message
    }
    card.addView(LinearLayout(ctx).apply {
      gravity = Gravity.CENTER_VERTICAL
      setPadding(px(40), px(24), px(32), px(24))
      addView(ImageView(ctx).apply { setImageResource(approvedIconResource("music")); setColorFilter(DinnerCardOverlay.ACCENT) },
          LinearLayout.LayoutParams(px(48), px(48)).apply { rightMargin = px(28) })
      addView(text(ctx, message, BANNER_SP, Color.WHITE, bold = true, lines = 1),
          LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
      addView(button(ctx, FollowMeCopy.UNDO, solid = true, px, onUndo),
          LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(88)))
    })
    card.addView(drainBar(ctx, px, FOLLOWED_MS))
    return FrameLayout(ctx).apply {
      addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
          .apply { setMargins(px(120), px(BANNER_TOP_DP), px(120), 0) })
    }
  }

  private fun glyph(ctx: Context, px: (Int) -> Int): View = FrameLayout(ctx).apply {
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(DinnerCardOverlay.GLYPH_BG) }
    addView(ImageView(ctx).apply { setImageResource(approvedIconResource("music")); setColorFilter(DinnerCardOverlay.ACCENT) },
        FrameLayout.LayoutParams(px(96), px(96), Gravity.CENTER))
  }

  private fun text(ctx: Context, value: String, size: Float, color: Int, bold: Boolean, lines: Int) = TextView(ctx).apply {
    text = value
    textSize = size
    setTextColor(color)
    if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    maxLines = lines
    ellipsize = TextUtils.TruncateAt.END
  }

  private fun button(ctx: Context, label: String, solid: Boolean, px: (Int) -> Int, onClick: () -> Unit): View =
      TextView(ctx).apply {
        text = label
        textSize = BUTTON_SP
        gravity = Gravity.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (solid) Color.BLACK else Color.WHITE)
        setPadding(px(52), 0, px(52), 0)
        background = rounded(if (solid) Color.WHITE else DinnerCardOverlay.TONAL, px(52).toFloat())
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { onClick() }
      }

  /** The yellow bar drains over the surface's lifetime (one animation, then nothing runs). */
  private fun drainBar(ctx: Context, px: (Int) -> Int, durationMs: Long): View = View(ctx).apply {
    setBackgroundColor(DinnerCardOverlay.ACCENT)
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(8))
    pivotX = 0f
    ObjectAnimator.ofFloat(this, View.SCALE_X, 1f, 0f).apply {
      duration = durationMs
      interpolator = LinearInterpolator()
      start()
    }
  }

  private fun pxOf(ctx: Context): (Int) -> Int {
    val density = ctx.resources.displayMetrics.density
    return { v -> (v * density).toInt() }
  }

  private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
}
