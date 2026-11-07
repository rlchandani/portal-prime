/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

internal data class DinnerCardModel(
    val title: String,
    val icon: String,
    val fromLine: String,
    val statusLine: String,
    val replyLine: String,
    val quiet: Boolean,
)

/** Drawable for a Call everyone glyph name; falls back to the approved catalog. */
internal object DinnerIcons {
  fun resource(name: String): Int = when (name.lowercase(Locale.ROOT)) {
    "plate" -> R.drawable.dinner_icon_plate
    "stairs" -> R.drawable.dinner_icon_stairs
    "door" -> R.drawable.dinner_icon_door
    "homeby" -> R.drawable.dinner_icon_homeby
    "keyboard" -> R.drawable.dinner_icon_keyboard
    "walk" -> R.drawable.dinner_icon_walk
    "reply" -> R.drawable.dinner_icon_reply
    "speaker" -> R.drawable.dinner_icon_speaker
    else -> approvedIconResource(name)
  }
}

/**
 * The receiving card: full width over whatever is showing (Home, an app, the photo-frame dream),
 * as a `TYPE_ACCESSIBILITY_OVERLAY` hosted by [BarWatchService], the same owner and window type as
 * [NotificationOverlay]. Replace-don't-stack; closes itself after [SHOW_MS], on a reply, on a
 * swipe, or on a tap outside the card. Main thread only.
 */
internal object DinnerCardOverlay {
  private const val TAG = "ImmortalDinnerCard"
  const val SHOW_MS = 30_000L
  private const val CARD = 0xFF2B2B2B.toInt()
  private const val SECONDARY = 0xFFB8B8B8.toInt()
  private const val ACCENT = 0xFFF2D58A.toInt()
  private const val GLYPH_BG = 0xFF4A4535.toInt()
  private const val TONAL = 0xFF3D3D3D.toInt()
  private const val SCRIM = 0x99000000.toInt()
  private val main = Handler(Looper.getMainLooper())
  private var host: AccessibilityService? = null
  private var wm: WindowManager? = null
  private var view: View? = null
  private val dismissRunnable = Runnable { hide() }

  fun attach(service: AccessibilityService) {
    main.post { host = service; wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager }
  }

  fun detach() {
    main.post { hide(); host = null; wm = null }
  }

  fun show(model: DinnerCardModel, onReply: (DinnerReply) -> Unit) {
    val ctx = host ?: run { Log.w(TAG, "card before the accessibility host attached; not shown"); return }
    val wmgr = wm ?: return
    hide()
    val root = buildRoot(ctx, model) { reply -> hide(); onReply(reply) }
    val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
    runCatching { wmgr.addView(root, lp); view = root }.onFailure { Log.w(TAG, "addView failed", it); return }
    main.postDelayed(dismissRunnable, SHOW_MS)
  }

  fun hide() {
    main.removeCallbacks(dismissRunnable)
    view?.let { v -> runCatching { wm?.removeView(v) } }
    view = null
  }

  private fun buildRoot(ctx: Context, model: DinnerCardModel, onReply: (DinnerReply) -> Unit): View {
    val dp = ctx.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()
    val scrim = FrameLayout(ctx).apply { setBackgroundColor(SCRIM); setOnClickListener { hide() } }
    val card = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      background = rounded(CARD, px(36).toFloat())
      clipToOutline = true
      isClickable = true // taps on the card itself don't fall through to the scrim
      contentDescription = model.title
    }
    card.addView(header(ctx, model, ::px))
    card.addView(View(ctx).apply { setBackgroundColor(0x22FFFFFF) },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1)).apply { setMargins(px(56), 0, px(56), 0) })
    card.addView(footer(ctx, model, ::px, onReply))
    card.addView(drainBar(ctx, ::px))
    swipeToDismiss(card, px(160).toFloat())
    scrim.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER).apply { setMargins(px(24), 0, px(24), 0) })
    return scrim
  }

  private fun header(ctx: Context, model: DinnerCardModel, px: (Int) -> Int): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(px(56), px(110), px(56), px(90))
    addView(FrameLayout(ctx).apply {
      background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GLYPH_BG) }
      addView(ImageView(ctx).apply { setImageResource(DinnerIcons.resource(model.icon)); setColorFilter(ACCENT) },
          FrameLayout.LayoutParams(px(120), px(120), Gravity.CENTER))
    }, LinearLayout.LayoutParams(px(250), px(250)).apply { rightMargin = px(60) })
    addView(LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      addView(TextView(ctx).apply {
        text = model.title
        setTextColor(Color.WHITE)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = DinnerCopy.titleSize(model.title)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
      })
      addView(TextView(ctx).apply { text = model.fromLine; setTextColor(SECONDARY); textSize = 30f; setPadding(0, px(14), 0, 0) })
    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
  }

  private fun footer(ctx: Context, model: DinnerCardModel, px: (Int) -> Int, onReply: (DinnerReply) -> Unit): View =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(56), px(40), px(56), px(40))
        addView(LinearLayout(ctx).apply {
          orientation = LinearLayout.VERTICAL
          addView(statusLine(ctx, if (model.quiet) "bell" else "speaker", model.statusLine, 26f, px))
          addView(statusLine(ctx, "reply", model.replyLine, 22f, px))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(button(ctx, DinnerReply.ON_MY_WAY.label, "walk", solid = false, px) { onReply(DinnerReply.ON_MY_WAY) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(104)).apply { rightMargin = px(20) })
        addView(button(ctx, DinnerReply.COMING.label, null, solid = true, px) { onReply(DinnerReply.COMING) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(104)))
      }

  private fun statusLine(ctx: Context, icon: String, text: String, size: Float, px: (Int) -> Int): View = LinearLayout(ctx).apply {
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, px(4), 0, px(4))
    addView(ImageView(ctx).apply { setImageResource(DinnerIcons.resource(icon)); setColorFilter(SECONDARY) },
        LinearLayout.LayoutParams(px(30), px(30)).apply { rightMargin = px(14) })
    addView(TextView(ctx).apply { this.text = text; setTextColor(SECONDARY); textSize = size; maxLines = 2 })
  }

  private fun button(ctx: Context, label: String, icon: String?, solid: Boolean, px: (Int) -> Int, onClick: () -> Unit): View =
      LinearLayout(ctx).apply {
        gravity = Gravity.CENTER
        setPadding(px(52), 0, px(52), 0)
        background = rounded(if (solid) Color.WHITE else TONAL, px(52).toFloat())
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { onClick() }
        if (icon != null) addView(ImageView(ctx).apply { setImageResource(DinnerIcons.resource(icon)); setColorFilter(Color.WHITE) },
            LinearLayout.LayoutParams(px(36), px(36)).apply { rightMargin = px(14) })
        addView(TextView(ctx).apply {
          text = label
          textSize = 32f
          typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
          setTextColor(if (solid) Color.BLACK else Color.WHITE)
        })
      }

  /** The yellow bar drains over the card's lifetime (one 30 s animation, then nothing runs). */
  private fun drainBar(ctx: Context, px: (Int) -> Int): View = View(ctx).apply {
    setBackgroundColor(ACCENT)
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(8))
    pivotX = 0f
    ObjectAnimator.ofFloat(this, View.SCALE_X, 1f, 0f).apply {
      duration = SHOW_MS
      interpolator = LinearInterpolator()
      start()
    }
  }

  @SuppressLint("ClickableViewAccessibility") // swipe is an extra; buttons and the scrim stay clickable
  private fun swipeToDismiss(card: View, threshold: Float) {
    var downX = 0f
    card.setOnTouchListener { v, e ->
      when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> { downX = e.rawX; true }
        MotionEvent.ACTION_MOVE -> { v.translationX = e.rawX - downX; true }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          if (kotlin.math.abs(e.rawX - downX) > threshold) hide() else v.animate().translationX(0f).setDuration(150).start()
          true
        }
        else -> false
      }
    }
  }

  private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
}

/** Short confirmations ("Sent “Coming” to the Family Area") through the existing toast overlay. */
internal object DinnerToast {
  fun show(text: String) {
    NotificationOverlay.show(NotifyPayload(title = text, message = "", image = null, sound = null,
        position = NotifyPayload.Position.TOP, durationSec = 5, volume = 0f, wakeScreen = false, onTap = null))
  }
}
