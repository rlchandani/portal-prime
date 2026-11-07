/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** What [AmbientMusicCardView.render] draws; computed from [AmbientMusicCardPolicy]. */
data class AmbientMusicCardModel(
    val title: String,
    val subtitle: String,
    /** Null hides the line (paused, or a live / unknown-length track). */
    val progress: Float?,
    val paused: Boolean,
    val controlsVisible: Boolean,
    val transportAvailable: Boolean,
)

/** What [AmbientMusicCardController] needs from the card's views (so it is JVM-testable). */
interface AmbientMusicCardSurface {
  fun render(model: AmbientMusicCardModel)
  fun hide()
  fun isShown(): Boolean
  fun contains(rawX: Float, rawY: Float): Boolean
  fun commandAt(rawX: Float, rawY: Float, state: NowPlayingState?): AmbientMusicCommand?
}

/**
 * Views for the screensaver now-playing card: art (~120dp), a 1–2 line title (~36sp), the
 * artist and a thin progress line; a walk-up row of previous / play-pause / next appears in
 * place below. Built from plain views with no animation. Nothing here is clickable: on a Dream
 * the host owns the touch stream, so [FaceRenderer.handleMusicCardTouch] hit-tests via
 * [contains] / [commandAt] instead (the same model as the timer alarm's slide-to-stop).
 */
class AmbientMusicCardView(private val context: Context, scalePercent: Int, showArt: Boolean) :
    AmbientMusicCardSurface {
  private val scale = scalePercent / 100f
  private val density = context.resources.displayMetrics.density

  val root = LinearLayout(context)
  val art = ImageView(context)
  val title = text(TITLE_SP * scale, INK, bold = true)
  val artist = text(ARTIST_SP * scale, INK_SECONDARY, bold = false)
  private val progress = ProgressLineView(context)
  private val controls = LinearLayout(context)
  private val previous = TransportGlyphView(context, TransportGlyphView.Glyph.PREVIOUS)
  private val playPause = TransportGlyphView(context, TransportGlyphView.Glyph.PAUSE)
  private val next = TransportGlyphView(context, TransportGlyphView.Glyph.NEXT)
  private val material = GradientDrawable().apply {
    cornerRadius = dp(28).toFloat()
    setColor(MATERIAL)
  }
  private var lastModel: AmbientMusicCardModel? = null

  init {
    root.orientation = LinearLayout.VERTICAL
    root.visibility = View.GONE
    // Constant padding: revealing the material must not shift the art or text.
    val pad = dp(CARD_PADDING_DP)
    root.setPadding(pad, pad, pad, pad)

    val row = LinearLayout(context)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    root.addView(row, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END })

    art.scaleType = ImageView.ScaleType.CENTER_CROP
    art.clipToOutline = true
    art.outlineProvider = object : ViewOutlineProvider() {
      override fun getOutline(v: View, o: Outline) {
        o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat())
      }
    }
    art.visibility = View.GONE
    val artSize = (dp(ART_DP) * scale).toInt()
    if (showArt) row.addView(art, LinearLayout.LayoutParams(artSize, artSize).apply { marginEnd = dp(22) })

    val col = LinearLayout(context)
    col.orientation = LinearLayout.VERTICAL
    col.minimumWidth = (dp(TEXT_MIN_WIDTH_DP) * scale).toInt()
    row.addView(col, LinearLayout.LayoutParams(WRAP, WRAP))
    title.maxLines = 2
    title.maxWidth = (dp(TEXT_MAX_WIDTH_DP) * scale).toInt()
    artist.maxLines = 1
    artist.maxWidth = title.maxWidth
    col.addView(title, LinearLayout.LayoutParams(WRAP, WRAP))
    col.addView(artist, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
    col.addView(progress, LinearLayout.LayoutParams(MATCH, dp(PROGRESS_HEIGHT_DP)).apply { topMargin = dp(14) })

    controls.orientation = LinearLayout.HORIZONTAL
    controls.gravity = Gravity.CENTER_VERTICAL
    controls.visibility = View.GONE
    val small = dp(SMALL_BUTTON_DP)
    val big = dp(PRIMARY_BUTTON_DP)
    previous.contentDescription = "Previous track"
    next.contentDescription = "Next track"
    controls.addView(previous, LinearLayout.LayoutParams(small, small))
    controls.addView(playPause, LinearLayout.LayoutParams(big, big).apply { marginStart = dp(36); marginEnd = dp(36) })
    controls.addView(next, LinearLayout.LayoutParams(small, small))
    root.addView(controls, LinearLayout.LayoutParams(WRAP, WRAP).apply {
      topMargin = dp(18)
      gravity = Gravity.CENTER_HORIZONTAL
    })
  }

  /** Apply [model]; unchanged fields are not re-set, so the 1s tick only redraws the line. */
  override fun render(model: AmbientMusicCardModel) {
    val before = lastModel
    lastModel = model
    root.visibility = View.VISIBLE
    if (before?.title != model.title) title.text = model.title
    if (before?.subtitle != model.subtitle) {
      artist.text = model.subtitle
      artist.visibility = if (model.subtitle.isBlank()) View.GONE else View.VISIBLE
    }
    progress.visibility = if (model.progress == null) View.GONE else View.VISIBLE
    model.progress?.let(progress::setFraction)
    if (before?.paused != model.paused) art.colorFilter = if (model.paused) DESATURATE else null
    if (before?.controlsVisible != model.controlsVisible) {
      controls.visibility = if (model.controlsVisible) View.VISIBLE else View.GONE
      root.background = if (model.controlsVisible) material else null
    }
    // Across the room a paused card dims; once someone walks up it reads at full strength.
    root.alpha = if (model.paused && !model.controlsVisible) PAUSED_ALPHA else 1f
    playPause.glyph = if (model.paused) TransportGlyphView.Glyph.PLAY else TransportGlyphView.Glyph.PAUSE
    playPause.contentDescription = if (model.paused) "Play" else "Pause"
    val enabledAlpha = if (model.transportAvailable) 1f else DISABLED_ALPHA
    controls.alpha = enabledAlpha
  }

  override fun hide() {
    root.visibility = View.GONE
    controls.visibility = View.GONE
    root.background = null
    lastModel = null
  }

  override fun isShown(): Boolean = root.visibility == View.VISIBLE

  /** Whether a screen point (raw px) falls on the visible card, with a forgiving margin. */
  override fun contains(rawX: Float, rawY: Float): Boolean =
      root.visibility == View.VISIBLE && hit(root, rawX, rawY, dp(TOUCH_MARGIN_DP))

  /** The transport command under a screen point while controls are shown, else null. */
  override fun commandAt(rawX: Float, rawY: Float, state: NowPlayingState?): AmbientMusicCommand? {
    if (controls.visibility != View.VISIBLE) return null
    val margin = dp(BUTTON_TOUCH_MARGIN_DP)
    return when {
      hit(playPause, rawX, rawY, margin) -> AmbientMusicCardPolicy.playPauseCommand(state)
      hit(previous, rawX, rawY, margin) -> AmbientMusicCommand.PREVIOUS
      hit(next, rawX, rawY, margin) -> AmbientMusicCommand.NEXT
      else -> null
    }
  }

  private fun hit(v: View, rawX: Float, rawY: Float, margin: Int): Boolean {
    if (v.width <= 0 || v.height <= 0) return false
    val at = IntArray(2)
    v.getLocationOnScreen(at)
    return rawX >= at[0] - margin && rawX <= at[0] + v.width + margin &&
        rawY >= at[1] - margin && rawY <= at[1] + v.height + margin
  }

  private fun text(sizeSp: Float, color: Int, bold: Boolean): TextView {
    val t = TextView(context)
    t.textSize = sizeSp
    t.setTextColor(color)
    t.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    t.ellipsize = TextUtils.TruncateAt.END
    t.setShadowLayer(8f, 0f, 2f, SHADOW)
    return t
  }

  private fun dp(v: Int): Int = (v * density).toInt()

  private companion object {
    const val ART_DP = 120
    const val TITLE_SP = 36f
    const val ARTIST_SP = 24f
    const val PROGRESS_HEIGHT_DP = 3
    const val TEXT_MIN_WIDTH_DP = 300
    const val TEXT_MAX_WIDTH_DP = 520
    const val CARD_PADDING_DP = 20
    const val SMALL_BUTTON_DP = 64
    const val PRIMARY_BUTTON_DP = 88
    const val TOUCH_MARGIN_DP = 16
    const val BUTTON_TOUCH_MARGIN_DP = 12
    const val PAUSED_ALPHA = 0.6f
    const val DISABLED_ALPHA = 0.4f
    // Warm neutrals from the music design review: ink, secondary ink, material ground.
    const val INK = 0xFFF4EEE4.toInt()
    const val INK_SECONDARY = 0xD9F4EEE4.toInt()
    const val MATERIAL = 0xE01B1915.toInt()
    const val SHADOW = 0x99000000.toInt()
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    val DESATURATE by lazy { ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
  }
}

/** A 3dp line: muted track with a brass fill. Redraws only when the fraction moves a pixel. */
class ProgressLineView(context: Context) : View(context) {
  private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK }
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BRASS }
  private var fraction = 0f

  fun setFraction(value: Float) {
    val next = value.coerceIn(0f, 1f)
    if (width > 0 && (kotlin.math.abs(next - fraction) * width) < 1f) return
    fraction = next
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    val r = height / 2f
    canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
    if (fraction > 0f) canvas.drawRoundRect(0f, 0f, width * fraction, height.toFloat(), r, r, fill)
  }

  private companion object {
    const val TRACK = 0x55F4EEE4
    const val BRASS = 0xFFD9B26A.toInt()
  }
}

/** Static transport glyph; play/pause sits on an ink disc, previous/next are bare. */
class TransportGlyphView(context: Context, initial: Glyph) : View(context) {
  enum class Glyph { PREVIOUS, PLAY, PAUSE, NEXT }

  var glyph: Glyph = initial
    set(value) {
      if (field != value) {
        field = value
        invalidate()
      }
    }

  private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
  private val ground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GROUND }
  private val path = Path()

  override fun onDraw(canvas: Canvas) {
    val w = width.toFloat()
    val h = height.toFloat()
    val cx = w / 2f
    val cy = h / 2f
    val primary = glyph == Glyph.PLAY || glyph == Glyph.PAUSE
    val paint = if (primary) ground else ink
    if (primary) canvas.drawCircle(cx, cy, minOf(w, h) / 2f, ink)
    val s = minOf(w, h) * (if (primary) 0.17f else 0.22f)
    path.reset()
    when (glyph) {
      Glyph.PLAY -> {
        path.moveTo(cx - s * 0.7f, cy - s)
        path.lineTo(cx + s, cy)
        path.lineTo(cx - s * 0.7f, cy + s)
        path.close()
        canvas.drawPath(path, paint)
      }
      Glyph.PAUSE -> {
        val bar = s * 0.55f
        canvas.drawRect(cx - s * 0.8f, cy - s, cx - s * 0.8f + bar, cy + s, paint)
        canvas.drawRect(cx + s * 0.8f - bar, cy - s, cx + s * 0.8f, cy + s, paint)
      }
      Glyph.PREVIOUS -> {
        canvas.drawRect(cx - s, cy - s, cx - s * 0.7f, cy + s, paint)
        path.moveTo(cx + s, cy - s)
        path.lineTo(cx - s * 0.6f, cy)
        path.lineTo(cx + s, cy + s)
        path.close()
        canvas.drawPath(path, paint)
      }
      Glyph.NEXT -> {
        canvas.drawRect(cx + s * 0.7f, cy - s, cx + s, cy + s, paint)
        path.moveTo(cx - s, cy - s)
        path.lineTo(cx + s * 0.6f, cy)
        path.lineTo(cx - s, cy + s)
        path.close()
        canvas.drawPath(path, paint)
      }
    }
  }

  private companion object {
    const val INK = 0xFFF4EEE4.toInt()
    const val GROUND = 0xFF1B1915.toInt()
  }
}
