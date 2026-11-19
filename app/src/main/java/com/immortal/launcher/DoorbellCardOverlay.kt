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
 * The doorbell card: the live door camera ([HaCameraFeed]) under "Someone's at the door" with
 * Dismiss and Open camera, drawn like [TimerDoneOverlay] (a `TYPE_ACCESSIBILITY_OVERLAY` hosted by
 * [BarWatchService]) so it shows over Home, any app or the photo-frame dream. A newer alert
 * replaces it; it closes itself after [DoorbellRing.SHOW_MS]. Main thread only.
 */
internal object DoorbellCardOverlay {
  private const val TAG = "HearthpaneDoorbellCard"
  private const val MARGIN_DP = 24
  private const val CARD_RADIUS_DP = 36
  private const val PAD_DP = 40
  private const val GLYPH_DP = 120
  private const val ICON_DP = 64
  private const val PICTURE_RADIUS_DP = 24
  private const val BUTTON_HEIGHT_DP = 96
  private const val TITLE_SP = 44f
  private const val LINE_SP = 26f
  private const val STATUS_SP = 22f
  private const val BUTTON_SP = 30f
  private const val PICTURE_BG = 0xFF101C22.toInt()
  private const val DIVIDER = 0x22FFFFFF

  data class Card(val alertId: String, val title: String, val line: String, val status: String, val quiet: Boolean,
                  val camera: String)

  private val main = Handler(Looper.getMainLooper())
  private var host: AccessibilityService? = null
  private var wm: WindowManager? = null
  @Volatile private var view: View? = null
  private var feed: HaCameraFeed? = null
  private val timeout = Runnable { remove() }

  val showing: Boolean get() = view != null

  fun attach(service: AccessibilityService) {
    main.post { host = service; wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager }
  }

  fun detach() {
    main.post { remove(); host = null; wm = null }
  }

  fun show(card: Card) {
    val ctx = host ?: run { Log.w(TAG, "doorbell card before the accessibility host attached; not shown"); return }
    val wmgr = wm ?: return
    remove()
    val picture = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    val placeholder = TextView(ctx).apply {
      text = if (card.camera.isBlank()) "No camera chosen for the doorbell" else "Connecting to camera…"
      setTextColor(DinnerCardOverlay.SECONDARY); textSize = LINE_SP; gravity = Gravity.CENTER
    }
    val root = buildRoot(ctx, card, picture, placeholder)
    val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // FLAG_SECURE: the live door camera never shows in a screenshot or screen capture (Jarvis's included).
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT)
    runCatching { wmgr.addView(root, lp); view = root }.onFailure { Log.w(TAG, "addView failed", it); return }
    // Full-screen card: it would cover the YouTube player, so music pauses while it shows.
    YouTubeMusicEngine.setCovered(MusicCover.DOORBELL, true)
    if (card.camera.isNotBlank()) {
      feed = HaCameraFeed(ctx, card.camera,
          onFrame = { bitmap -> picture.setImageBitmap(bitmap); placeholder.visibility = View.GONE },
          onUnavailable = { placeholder.text = "Camera unavailable on this Portal" }).also { it.start() }
    }
    main.postDelayed(timeout, DoorbellRing.SHOW_MS)
  }

  fun hide() = remove()

  private fun remove() {
    main.removeCallbacks(timeout)
    feed?.stop(); feed = null
    view?.let { v -> runCatching { wm?.removeView(v) } }
    view = null
    YouTubeMusicEngine.setCovered(MusicCover.DOORBELL, false)
  }

  private fun openCamera(ctx: Context, camera: String, title: String) {
    remove()
    runCatching { ctx.startActivity(haCameraIntent(ctx, camera, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Log.w(TAG, "could not open the camera view", it) }
  }

  private fun buildRoot(ctx: Context, card: Card, picture: ImageView, placeholder: TextView): View {
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
    val frame = FrameLayout(ctx).apply {
      background = rounded(PICTURE_BG, px(PICTURE_RADIUS_DP).toFloat()); clipToOutline = true
      contentDescription = "Live camera"
      addView(picture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
      addView(placeholder, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    body.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        .apply { setMargins(px(PAD_DP), 0, px(PAD_DP), px(PAD_DP / 2)) })
    body.addView(View(ctx).apply { setBackgroundColor(DIVIDER) },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1)).apply { setMargins(px(PAD_DP), 0, px(PAD_DP), 0) })
    body.addView(footer(ctx, card, px))
    scrim.addView(body, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        .apply { setMargins(px(MARGIN_DP), px(MARGIN_DP), px(MARGIN_DP), px(MARGIN_DP)) })
    return scrim
  }

  private fun header(ctx: Context, card: Card, px: (Int) -> Int): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(px(PAD_DP), px(PAD_DP), px(PAD_DP), px(PAD_DP / 2))
    addView(FrameLayout(ctx).apply {
      background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(DinnerCardOverlay.GLYPH_BG) }
      addView(ImageView(ctx).apply { setImageResource(DinnerIcons.resource("door")); setColorFilter(DinnerCardOverlay.ACCENT) },
          FrameLayout.LayoutParams(px(ICON_DP), px(ICON_DP), Gravity.CENTER))
    }, LinearLayout.LayoutParams(px(GLYPH_DP), px(GLYPH_DP)).apply { rightMargin = px(PAD_DP) })
    addView(LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      addView(TextView(ctx).apply {
        text = card.title; setTextColor(Color.WHITE); textSize = TITLE_SP; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      })
      addView(TextView(ctx).apply { text = card.line; setTextColor(DinnerCardOverlay.SECONDARY); textSize = LINE_SP; setPadding(0, px(10), 0, 0) })
    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
  }

  private fun footer(ctx: Context, card: Card, px: (Int) -> Int): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(px(PAD_DP), px(PAD_DP / 2), px(PAD_DP), px(PAD_DP / 2))
    addView(LinearLayout(ctx).apply {
      gravity = Gravity.CENTER_VERTICAL
      addView(ImageView(ctx).apply {
        setImageResource(DinnerIcons.resource(if (card.quiet) "bell" else "speaker")); setColorFilter(DinnerCardOverlay.SECONDARY)
      }, LinearLayout.LayoutParams(px(30), px(30)).apply { rightMargin = px(14) })
      addView(TextView(ctx).apply { text = card.status; setTextColor(DinnerCardOverlay.SECONDARY); textSize = STATUS_SP; maxLines = 2 })
    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    if (card.camera.isNotBlank()) {
      addView(button(ctx, "Open camera", solid = false, px) { openCamera(ctx, card.camera, DoorbellEntities.prettyName(card.camera)) },
          LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(BUTTON_HEIGHT_DP)).apply { rightMargin = px(20) })
    }
    addView(button(ctx, "Dismiss", solid = true, px) { remove() },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(BUTTON_HEIGHT_DP)))
  }

  private fun button(ctx: Context, label: String, solid: Boolean, px: (Int) -> Int, onClick: () -> Unit): View =
      TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(px(48), 0, px(48), 0)
        textSize = BUTTON_SP
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (solid) Color.BLACK else Color.WHITE)
        background = rounded(if (solid) Color.WHITE else DinnerCardOverlay.TONAL, px(BUTTON_HEIGHT_DP / 2).toFloat())
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { onClick() }
      }

  private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
}
