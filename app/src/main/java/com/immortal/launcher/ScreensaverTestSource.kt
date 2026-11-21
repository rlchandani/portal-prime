/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A verification-only photo source of generated landscapes, so screensaver captures never
 * contain a household's own photos. It exists only in builds compiled with
 * `PORTAL_SCREENSAVER_TEST_SOURCE` (never release), is switched on by the authenticated
 * `screensaver_test_source` fleet action, and expires on its own.
 */
internal object ScreensaverTestSource {
  private const val PREFS = "screensaver_test_source"
  private const val KEY_UNTIL = "until"
  private const val KEY_NIGHT_UNTIL = "night_until"
  private const val KEY_PIN_MODE = "pin_mode"
  private const val KEY_PIN_FACE = "pin_face"
  private const val KEY_PIN_INTERVAL = "pin_interval_sec"
  const val MAX_MINUTES = 240
  /** A forced dark room arrives after the day frame has drawn, as when the lights go out. */
  const val FORCED_NIGHT_DELAY_MS = 12_000L
  // The marker's centre as a fraction of the scene: the framing target, so it stays inside every
  // cover crop (a portrait scene on a landscape panel keeps only its middle band).
  internal const val MARKER_X = .5f
  internal const val MARKER_Y = .45f

  /** A generated scene: aspect, palette, and a synthetic caption. */
  class Scene(
      val width: Int, val height: Int, val sky: IntArray, val hills: IntArray, val sun: Int,
      val sunX: Float, val sunY: Float, val place: String, val date: String, val memory: Boolean = false,
  )

  val scenes = listOf(
      Scene(3, 2, intArrayOf(0xFF2D5F8C.toInt(), 0xFFF2C06B.toInt()), intArrayOf(0xFF3A4F3C.toInt(), 0xFF22301F.toInt(), 0xFF141C13.toInt()),
          0xFFFFE2A8.toInt(), .62f, .58f, "Test Valley", "June 2024"),
      Scene(2, 3, intArrayOf(0xFF5A2A6E.toInt(), 0xFFE0763A.toInt()), intArrayOf(0xFF3B2340.toInt(), 0xFF24152A.toInt(), 0xFF120A16.toInt()),
          0xFFFFD08A.toInt(), .4f, .52f, "Placeholder Point", "August 2023", memory = true),
      Scene(16, 9, intArrayOf(0xFFBFD9EA.toInt(), 0xFFF4F1EA.toInt()), intArrayOf(0xFFE6EEF2.toInt(), 0xFFCBD8DF.toInt(), 0xFF9FB3BE.toInt()),
          0xFFFFFFFF.toInt(), .3f, .3f, "Snowfield Sample", "January 2025"),
      Scene(4, 3, intArrayOf(0xFF3A4F9C.toInt(), 0xFF88D0C4.toInt()), intArrayOf(0xFF2E6B5E.toInt(), 0xFF1E4A40.toInt(), 0xFF0F2622.toInt()),
          0xFFFFF4D6.toInt(), .7f, .4f, "Generated Lake", "May 2022"),
      Scene(3, 4, intArrayOf(0xFF0E1A33.toInt(), 0xFF6B4A8C.toInt()), intArrayOf(0xFF2A2440.toInt(), 0xFF1A1630.toInt(), 0xFF0C0A18.toInt()),
          0xFFF3E6C8.toInt(), .55f, .25f, "Night Test Ridge", "October 2024"),
      Scene(1, 1, intArrayOf(0xFFB33A52.toInt(), 0xFFF0A65A.toInt()), intArrayOf(0xFF6B2A2E.toInt(), 0xFF451A1F.toInt(), 0xFF240C10.toInt()),
          0xFFFFE9B0.toInt(), .5f, .62f, "Sample Coast", "July 2021"),
  )

  fun available(): Boolean = BuildConfig.PORTAL_SCREENSAVER_TEST_SOURCE

  fun active(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean =
      available() && until(context) > nowMs

  fun until(context: Context): Long =
      processPrefs(context, PREFS).getLong(KEY_UNTIL, 0L)

  /**
   * Enable for [minutes] (clamped to 1..[MAX_MINUTES]); false when this build has no test source.
   * [night] also forces the dark-room state for the same span (operator verification of the night
   * colours without the light sensor); it expires with the source and is dropped by a plain enable.
   * [pin] holds one screensaver mode at a fixed interval for the same span, so a performance A/B
   * sees the same mode change on the same beat every run; a plain enable drops it.
   */
  fun enable(
      context: Context, minutes: Int, nowMs: Long = System.currentTimeMillis(), night: Boolean = false,
      pin: Pin? = null,
  ): Boolean {
    if (!available()) return false
    val until = nowMs + minutes.coerceIn(1, MAX_MINUTES) * 60_000L
    val edit = processPrefs(context, PREFS).edit()
    edit.putLong(KEY_UNTIL, until)
    if (night) edit.putLong(KEY_NIGHT_UNTIL, until) else edit.remove(KEY_NIGHT_UNTIL)
    if (pin != null) {
      edit.putString(KEY_PIN_MODE, pin.mode)
      edit.putInt(KEY_PIN_INTERVAL, pin.intervalSec)
      if (pin.face != null) edit.putString(KEY_PIN_FACE, pin.face) else edit.remove(KEY_PIN_FACE)
    } else {
      edit.remove(KEY_PIN_MODE)
      edit.remove(KEY_PIN_INTERVAL)
      edit.remove(KEY_PIN_FACE)
    }
    return edit.commit()
  }

  fun disable(context: Context): Boolean {
    val edit = processPrefs(context, PREFS).edit()
    listOf(KEY_UNTIL, KEY_NIGHT_UNTIL, KEY_PIN_MODE, KEY_PIN_INTERVAL, KEY_PIN_FACE).forEach { edit.remove(it) }
    return edit.commit()
  }

  /**
   * One screensaver mode held at a fixed photo interval (operator performance runs only). [face]
   * optionally shows one photo-frame clock face for the same span instead of the household's, so
   * a face can be verified without changing a synced setting.
   */
  data class Pin(val mode: String, val intervalSec: Int, val face: String? = null) {
    companion object {
      /**
       * A valid pin, or null when [mode] isn't a known mode, [intervalSec] is outside 5..600 s, or
       * [face] is given but isn't a catalog face.
       */
      fun of(mode: String, intervalSec: Int, face: String? = null): Pin? =
          if (mode in ScreensaverConfig.MODES && intervalSec == ScreensaverConfig.clampInterval(intervalSec) &&
              (face == null || FaceCatalog.entries.any { it.id == face }))
            Pin(mode, intervalSec, face) else null
    }
  }

  /** The operator pin while the test source is active, else null. */
  fun pin(context: Context, nowMs: Long = System.currentTimeMillis()): Pin? {
    if (!active(context, nowMs)) return null
    val prefs = processPrefs(context, PREFS)
    val mode = prefs.getString(KEY_PIN_MODE, null) ?: return null
    return Pin.of(mode, prefs.getInt(KEY_PIN_INTERVAL, 0), prefs.getString(KEY_PIN_FACE, null))
  }

  /** [settings] with [pin]'s mode and interval in place of the household's, or unchanged. */
  fun pinned(settings: ScreensaverConfig.Settings, pin: Pin?): ScreensaverConfig.Settings =
      if (pin == null) settings else settings.copy(mode = pin.mode, intervalSec = pin.intervalSec)

  /** True while an operator forced the dark room on together with an active test source. */
  fun forcedNight(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean =
      active(context, nowMs) &&
          processPrefs(context, PREFS).getLong(KEY_NIGHT_UNTIL, 0L) > nowMs

  fun scene(index: Int): Scene = scenes[Math.floorMod(index, scenes.size)]

  /**
   * Synthetic events for the calendar panel while the test source is on, so a verification
   * capture never shows a household's real agenda: one in progress, one starting soon, two later.
   */
  fun events(nowMs: Long): List<CalendarFeed.Event> {
    val minute = 60_000L
    val start = nowMs - nowMs % minute
    return listOf(
        CalendarFeed.Event(start - 20 * minute, start + 25 * minute, "Sample stand-up", false),
        CalendarFeed.Event(start + 25 * minute, start + 70 * minute, "Placeholder lesson", false),
        CalendarFeed.Event(start + 3 * 60 * minute, start + 4 * 60 * minute, "Test delivery window", false),
        CalendarFeed.Event(start + 5 * 60 * minute, start + 6 * 60 * minute, "Generated book club", false),
    )
  }

  /** Draw scene [index] with its long edge at [longEdge] pixels. */
  fun photo(index: Int, longEdge: Int): Bitmap {
    val s = scene(index)
    val edge = longEdge.coerceIn(320, SlideshowDecodeBudget.MAX_EDGE)
    val w = if (s.width >= s.height) edge else (edge * s.width.toFloat() / s.height).roundToInt()
    val h = if (s.width >= s.height) (edge * s.height.toFloat() / s.width).roundToInt() else edge
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    paint.shader = LinearGradient(0f, 0f, 0f, h * .7f, s.sky[0], s.sky[1], Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    val sunR = minOf(w, h) * .07f
    paint.shader = RadialGradient(w * s.sunX, h * s.sunY, sunR * 4f,
        intArrayOf(s.sun, s.sun and 0xFFFFFF), floatArrayOf(.2f, 1f), Shader.TileMode.CLAMP)
    canvas.drawCircle(w * s.sunX, h * s.sunY, sunR * 4f, paint)
    paint.shader = null
    paint.color = s.sun
    canvas.drawCircle(w * s.sunX, h * s.sunY, sunR, paint)
    s.hills.forEachIndexed { layer, color ->
      val base = h * (.55f + layer * .14f)
      val path = Path().apply {
        moveTo(0f, h.toFloat())
        var x = 0f
        while (x <= w) {
          val y = base - h * .06f * (sin(x / w * (3.1f + layer) * Math.PI + layer).toFloat() + .4f)
          lineTo(x, y)
          x += w / 64f
        }
        lineTo(w.toFloat(), h.toFloat())
        close()
      }
      paint.color = color
      canvas.drawPath(path, paint)
    }
    // A visible marker: anything captured from this source is self-evidently a test scene. It sits
    // well inside the frame, because cover framing and the settle zoom crop the outer edges away
    // (a corner marker was cut off on a Portal+), and it stays bright enough to read under the
    // Night photo's warm dim veil.
    paint.shader = null
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = minOf(w, h) * .036f
    paint.color = Color.argb(220, 255, 255, 255)
    paint.setShadowLayer(paint.textSize * .15f, 0f, 0f, Color.argb(200, 0, 0, 0))
    canvas.drawText("TEST SCENE ${Math.floorMod(index, scenes.size) + 1} - generated", w * MARKER_X, h * MARKER_Y, paint)
    return bitmap
  }
}
