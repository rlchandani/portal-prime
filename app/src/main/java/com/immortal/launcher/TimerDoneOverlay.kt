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
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The full-screen "Timer done" card, drawn like [DinnerCardOverlay] (a `TYPE_ACCESSIBILITY_OVERLAY`
 * hosted by [BarWatchService]) so it shows over Home, any app or the photo-frame dream. It stays
 * until Stop or +1 min here, or until the timer is stopped in another room ([hide]); a tap outside
 * does nothing, so a timer can't be silenced by accident. One card at a time. Main thread only.
 */
internal object TimerDoneOverlay {
  private const val TAG = "HearthpaneTimerCard"
  private const val CARD_RADIUS_DP = 36
  private const val GLYPH_DP = 220
  private const val ICON_DP = 110
  private const val BUTTON_HEIGHT_DP = 104
  private const val TITLE_SP = 56f
  private const val LINE_SP = 30f
  private const val STATUS_SP = 24f
  private const val BUTTON_SP = 32f

  data class Card(val timerId: String, val title: String, val line: String, val status: String, val quiet: Boolean)

  private val main = Handler(Looper.getMainLooper())
  private var host: AccessibilityService? = null
  private var wm: WindowManager? = null
  private var view: View? = null
  private var currentId: String? = null

  val showing: Boolean get() = view != null

  fun attach(service: AccessibilityService) {
    main.post {
      host = service
      wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
      SharedTimerPresenter.hostReady(service.applicationContext) // a ring from before the host attached
    }
  }

  fun detach() {
    main.post { remove(); host = null; wm = null }
  }

  fun show(card: Card, onStop: () -> Unit, onAddMinute: () -> Unit) {
    val ctx = host ?: run { Log.w(TAG, "timer card before the accessibility host attached; not shown"); return }
    val wmgr = wm ?: return
    remove()
    val root = buildRoot(ctx, card, onStop, onAddMinute)
    val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
    runCatching { wmgr.addView(root, lp); view = root; currentId = card.timerId }
        .onSuccess { YouTubeMusicEngine.setCovered(MusicCover.TIMER, true) } // it would cover the player
        .onFailure { Log.w(TAG, "addView failed", it) }
  }

  /** Closes the card if it shows [timerId] (another room stopped it, or it was answered here). */
  fun hide(timerId: String) {
    if (currentId == timerId) remove()
  }

  private fun remove() {
    view?.let { v -> runCatching { wm?.removeView(v) } }
    view = null
    currentId = null
    YouTubeMusicEngine.setCovered(MusicCover.TIMER, false)
  }

  private fun buildRoot(ctx: Context, card: Card, onStop: () -> Unit, onAddMinute: () -> Unit): View {
    val px = { v: Int -> (v * ctx.resources.displayMetrics.density).toInt() }
    val scrim = FrameLayout(ctx).apply { setBackgroundColor(DinnerCardOverlay.SCRIM); isClickable = true }
    val body = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      background = rounded(DinnerCardOverlay.CARD, px(CARD_RADIUS_DP).toFloat())
      clipToOutline = true
      isClickable = true
      contentDescription = "${card.title}. ${card.line}"
    }
    body.addView(header(ctx, card, px))
    body.addView(View(ctx).apply { setBackgroundColor(DIVIDER) },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1)).apply { setMargins(px(56), 0, px(56), 0) })
    body.addView(footer(ctx, card, px, onStop, onAddMinute))
    scrim.addView(body, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER).apply { setMargins(px(24), 0, px(24), 0) })
    return scrim
  }

  private fun header(ctx: Context, card: Card, px: (Int) -> Int): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(px(56), px(96), px(56), px(80))
    addView(FrameLayout(ctx).apply {
      background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(DinnerCardOverlay.GLYPH_BG) }
      addView(ImageView(ctx).apply { setImageResource(DinnerIcons.resource("timer")); setColorFilter(DinnerCardOverlay.ACCENT) },
          FrameLayout.LayoutParams(px(ICON_DP), px(ICON_DP), Gravity.CENTER))
    }, LinearLayout.LayoutParams(px(GLYPH_DP), px(GLYPH_DP)).apply { rightMargin = px(56) })
    addView(LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      addView(TextView(ctx).apply {
        text = card.title
        setTextColor(Color.WHITE)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = TITLE_SP
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
      })
      addView(TextView(ctx).apply { text = card.line; setTextColor(DinnerCardOverlay.SECONDARY); textSize = LINE_SP; setPadding(0, px(14), 0, 0) })
    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
  }

  private fun footer(ctx: Context, card: Card, px: (Int) -> Int, onStop: () -> Unit, onAddMinute: () -> Unit): View =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(56), px(40), px(56), px(40))
        addView(LinearLayout(ctx).apply {
          gravity = Gravity.CENTER_VERTICAL
          addView(ImageView(ctx).apply {
            setImageResource(DinnerIcons.resource(if (card.quiet) "bell" else "speaker")); setColorFilter(DinnerCardOverlay.SECONDARY)
          }, LinearLayout.LayoutParams(px(30), px(30)).apply { rightMargin = px(14) })
          addView(TextView(ctx).apply { text = card.status; setTextColor(DinnerCardOverlay.SECONDARY); textSize = STATUS_SP; maxLines = 2 })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(button(ctx, "+1 min", solid = false, px) { remove(); onAddMinute() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(BUTTON_HEIGHT_DP)).apply { rightMargin = px(20) })
        addView(button(ctx, "Stop", solid = true, px) { remove(); onStop() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(BUTTON_HEIGHT_DP)))
      }

  private fun button(ctx: Context, label: String, solid: Boolean, px: (Int) -> Int, onClick: () -> Unit): View =
      TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(px(56), 0, px(56), 0)
        textSize = BUTTON_SP
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (solid) Color.BLACK else Color.WHITE)
        background = rounded(if (solid) Color.WHITE else DinnerCardOverlay.TONAL, px(BUTTON_HEIGHT_DP / 2).toFloat())
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { onClick() }
      }

  private const val DIVIDER = 0x22FFFFFF

  private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
}
