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
 * replaces it; it closes itself after [DoorbellRing.SHOW_MS]. An accessibility unbind or an app update
 * doesn't close it early: it is held ([OverlayHold]) and comes back when the host binds. Main thread only.
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
                  val camera: String) {
    /** The held card on disk ([OverlayHoldStore]): an app update must not drop someone at the door. */
    companion object {
      const val SLOT = "doorbell"
      private const val MAX_TEXT = 200

      fun encode(c: Card): org.json.JSONObject = org.json.JSONObject().put("alert", c.alertId).put("title", c.title)
          .put("line", c.line).put("status", c.status).put("quiet", c.quiet).put("camera", c.camera)

      fun decode(o: org.json.JSONObject?): Card? {
        if (o == null) return null
        val id = o.optString("alert").takeIf { it.isNotBlank() && it.length <= MAX_TEXT } ?: return null
        val title = o.optString("title").take(MAX_TEXT).ifBlank { return null }
        val camera = o.optString("camera").takeIf { it.length <= MAX_TEXT } ?: return null
        return Card(id, title, o.optString("line").take(MAX_TEXT), o.optString("status").take(MAX_TEXT), o.optBoolean("quiet"), camera)
      }
    }
  }

  private val main = Handler(Looper.getMainLooper())
  private var host: AccessibilityService? = null
  private var wm: WindowManager? = null
  private var app: Context? = null
  @Volatile private var view: View? = null
  private var feed: HaCameraFeed? = null
  /** The card, kept through an accessibility unbind or an app update (see [OverlayHold]). */
  private var hold = OverlayHold<Card>()
  private var restored = false
  private val timeout = Runnable { expire() }

  val showing: Boolean get() = view != null

  /** The host bound: a card parked by an unbind (or restored after an update) comes back for the rest of its minute. */
  fun attach(service: AccessibilityService) {
    main.post {
      host = service; wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
      restoreOnce(service.applicationContext)
      hold = hold.hostReady(OverlayHoldStore.now())
      if (hold.shown) { Log.i(TAG, "doorbell card back after a rebind"); draw() } else persist()
    }
  }

  /** [service] unbound: the system takes the window; the card stays held until it binds again. */
  fun detach(service: AccessibilityService) {
    main.post {
      if (host !== service) return@post // an older instance's unbind after the new one bound
      removeView()
      if (hold.held != null) Log.i(TAG, "accessibility host unbound; doorbell card held")
      hold = hold.hostLost(); host = null; wm = null
    }
  }

  /** Main thread. A newer card replaces the one held, showing or parked. */
  fun show(context: Context, card: Card) {
    restoreOnce(context)
    val now = OverlayHoldStore.now()
    hold = hold.offer(card.alertId, card, now + DoorbellRing.SHOW_MS)
    persist()
    if (hold.shown) draw() else { removeView(); schedule(now); Log.w(TAG, "doorbell card held until the accessibility host binds") }
  }

  /** Dismissed: it is not brought back by a rebind. */
  fun hide() {
    hold = hold.dismiss()
    persist()
    removeView()
    main.removeCallbacks(timeout)
  }

  private fun draw() {
    val card = hold.held?.payload ?: return
    val ctx = host ?: return
    val wmgr = wm ?: return
    val now = OverlayHoldStore.now()
    if (hold.remainingMs(now) == null) { expire(); return }
    removeView()
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
    schedule(now)
    // No window (the host is going away): stay held for the next bind.
    runCatching { wmgr.addView(root, lp); view = root }.onFailure { Log.w(TAG, "addView failed", it); hold = hold.hostLost(); return }
    // Full-screen card: it would cover the YouTube player, so music pauses while it shows.
    YouTubeMusicEngine.setCovered(MusicCover.DOORBELL, true)
    if (card.camera.isNotBlank()) {
      feed = HaCameraFeed(ctx, card.camera,
          onFrame = { bitmap -> picture.setImageBitmap(bitmap); placeholder.visibility = View.GONE },
          onUnavailable = { placeholder.text = "Camera unavailable on this Portal" }).also { it.start() }
    }
  }

  private fun schedule(now: Long) {
    main.removeCallbacks(timeout)
    hold.remainingMs(now)?.let { main.postDelayed(timeout, it) }
  }

  private fun expire() {
    hold = hold.expire(OverlayHoldStore.now())
    if (hold.held == null) { persist(); removeView() } else schedule(OverlayHoldStore.now())
  }

  private fun restoreOnce(ctx: Context) {
    app = ctx.applicationContext
    if (restored) return
    restored = true
    if (hold.held != null) return
    val r = OverlayHoldStore.load(ctx, Card.SLOT) ?: return
    val card = Card.decode(r.payload) ?: return
    Log.i(TAG, "doorbell card restored after a restart")
    hold = hold.offer(r.id, card, r.deadlineMs)
    schedule(OverlayHoldStore.now())
  }

  private fun persist() {
    val ctx = app ?: return
    OverlayHoldStore.save(ctx, Card.SLOT, hold.held?.let { OverlayHold.Held(it.id, Card.encode(it.payload), it.deadlineMs) })
  }

  private fun removeView() {
    feed?.stop(); feed = null
    view?.let { v -> runCatching { wm?.removeView(v) } }
    view = null
    YouTubeMusicEngine.setCovered(MusicCover.DOORBELL, false)
  }

  private fun openCamera(ctx: Context, camera: String, title: String) {
    hide()
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
    addView(button(ctx, "Dismiss", solid = true, px) { hide() },
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
