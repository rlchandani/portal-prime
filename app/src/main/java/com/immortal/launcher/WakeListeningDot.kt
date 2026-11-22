/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.immortal.launcher.jarvis.wake.HearthpaneWake

/** The ember "listening for Hey Jarvis" dot: Hearthpane's mark at dot size ([HearthpaneMark] tokens). */
internal object WakeListeningDotStyle {
  const val SIZE_DP = 10
  const val INSET_DP = 14
  const val DESCRIPTION = "Listening for “Hey Jarvis”"
  /** Night clock: the dot sits under the same veil as the clock's own ember mark. */
  const val NIGHT_ALPHA = .58f
  /** Burn-in drift radius: the dot never brands one spot on an always-on frame ([AntiBurnIn]). */
  const val DRIFT_DP = 4
  const val DRIFT_TICK_MS = 60_000L
}

/** Home: shown only while the detector really listens ([com.immortal.launcher.jarvis.wake.WakeIndicator]). */
@Composable
internal fun WakeListeningDot(modifier: Modifier = Modifier) {
  val listening by HearthpaneWake.listening.collectAsState()
  if (!listening) return
  HearthpaneGlyph(WakeListeningDotStyle.SIZE_DP.dp,
      modifier.semantics { contentDescription = WakeListeningDotStyle.DESCRIPTION })
}

/**
 * The photo frame's dot: a plain View over the frame (the frame is View-based), top end, with the glyph's gradient
 * and glow. Visibility and night alpha are set by the host; [drift] nudges it once a minute.
 */
internal class WakeListeningDotView(context: Context) : View(context) {
  private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)
  private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
  private val density = context.resources.displayMetrics.density
  private val glyphPx = WakeListeningDotStyle.SIZE_DP * density

  init {
    contentDescription = WakeListeningDotStyle.DESCRIPTION
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    visibility = GONE
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    val top = (h - glyphPx) / 2
    glyph.shader = LinearGradient(0f, top, 0f, top + glyphPx, HearthpaneMark.EmberLight.toArgb(),
        HearthpaneMark.Ember.toArgb(), Shader.TileMode.CLAMP)
    val glowColor = HearthpaneMark.EmberLight.copy(alpha = HearthpaneMark.GLOW_ALPHA).toArgb()
    glow.shader = RadialGradient(w / 2f, h / 2f, w / 2f, glowColor, 0, Shader.TileMode.CLAMP)
  }

  override fun onDraw(canvas: Canvas) {
    val cx = width / 2f
    val cy = height / 2f
    canvas.drawCircle(cx, cy, width / 2f, glow)
    canvas.drawCircle(cx, cy, glyphPx / 2, glyph)
  }

  fun show(listening: Boolean, night: Boolean) {
    alpha = if (night) WakeListeningDotStyle.NIGHT_ALPHA else 1f
    visibility = if (listening) VISIBLE else GONE
  }

  /** One burn-in step; a hidden dot takes none (a translation change would damage the frame for nothing). */
  fun drift(nowMs: Long, enabled: Boolean) {
    val shift = AntiBurnIn.overlayShift(visibility == VISIBLE, enabled, nowMs,
        WakeListeningDotStyle.DRIFT_DP * density) ?: return
    translationX = shift.x
    translationY = shift.y
  }

  companion object {
    /** Layout for the frame host's content FrameLayout: glyph plus its glow, inset from the top-end corner. */
    fun layoutParams(context: Context): FrameLayout.LayoutParams {
      val density = context.resources.displayMetrics.density
      val box = (WakeListeningDotStyle.SIZE_DP * HearthpaneMark.GLOW_REACH * 2 * density).toInt()
      val inset = (WakeListeningDotStyle.INSET_DP * density).toInt()
      return FrameLayout.LayoutParams(box, box, Gravity.TOP or Gravity.END).apply { setMargins(0, inset, inset, 0) }
    }
  }
}
