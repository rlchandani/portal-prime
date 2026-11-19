/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.SlideshowGeometry.Transform
import kotlin.math.roundToInt

/**
 * Photo-matched colour and adaptive readability for the Hearth overlay. Everything here is a
 * pure function of the per-photo [PhotoAnalysis] and its on-screen framing, so the overlay can
 * cross-fade its ink, scrims and accent in step with the photo dissolve.
 */
object HearthReadability {
  /** The overlay zones whose brightness drives ink and local scrims, as viewport fractions. */
  enum class Zone(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    CLOCK(0f, .6f, .5f, 1f),
    CAPTION(.6f, .7f, 1f, 1f),
    PANEL(.6f, 0f, 1f, .45f),
  }

  /** Text treatment for one zone. Colours are ARGB; the scrim is a local radial wash. */
  data class Ink(
      val dark: Boolean,
      val ink: Int,
      val sub: Int,
      val scrim: Int,
      val shadow: Int,
  ) {
    fun mix(to: Ink, f: Float): Ink = Ink(if (f < .5f) dark else to.dark,
        lerpArgb(ink, to.ink, f), lerpArgb(sub, to.sub, f), lerpArgb(scrim, to.scrim, f),
        lerpArgb(shadow, to.shadow, f))
  }

  /** Everything the overlay needs from one photo. */
  data class Appearance(val accent: Int, val clock: Ink, val caption: Ink, val panel: Ink) {
    fun mix(to: Appearance, f: Float): Appearance = Appearance(lerpArgb(accent, to.accent, f),
        clock.mix(to.clock, f), caption.mix(to.caption, f), panel.mix(to.panel, f))

    companion object {
      fun neutral(night: Boolean) = Appearance(accent(null, night),
          inkFor(.4f, 0f, night), inkFor(.4f, 0f, night), inkFor(.4f, 0f, night))
    }
  }

  const val EMBER = 0xFFF0A65A.toInt()
  private const val NIGHT_EMBER = 0xFFDEA06C.toInt()
  const val DAY_INK = 0xFFFBF7F1.toInt()
  private const val DAY_SUB = 0xC7FBF7F1.toInt()
  const val NIGHT_INK = 0xD1F3D0AC.toInt()
  private const val NIGHT_SUB = 0x9EE8BE96.toInt()
  private const val DARK_INK = 0xFF1F1914.toInt()
  private const val DARK_SUB = 0xBD1F1914.toInt()
  /** A zone this bright and this calm reads better with dark ink than with a heavy scrim. */
  const val DARK_INK_FROM = .74f
  const val DARK_INK_MAX_TEXTURE = .12f

  /**
   * The photo's hue pushed into a readable, warm-leaning lightness band (mock `accentFrom`).
   * Monochrome photos keep the Hearthpane ember.
   */
  fun accent(dominant: Int?, night: Boolean): Int {
    if (dominant == null) return if (night) NIGHT_EMBER else EMBER
    val hsl = FloatArray(3)
    PhotoAnalysis.rgbToHsl(dominant shr 16 and 255, dominant shr 8 and 255, dominant and 255, hsl)
    return hslToArgb(hsl[0], if (night) .46f else .62f, if (night) .66f else .76f)
  }

  /** Ink for a zone of the given brightness ([luminance] is L* in 0..1) and busyness. */
  fun inkFor(luminance: Float, texture: Float, night: Boolean): Ink {
    val lum = if (luminance.isFinite()) luminance.coerceIn(0f, 1f) else .4f
    val busy = if (texture.isFinite()) texture.coerceIn(0f, 1f) else 0f
    if (!night && lum >= DARK_INK_FROM && busy <= DARK_INK_MAX_TEXTURE) {
      val wash = .16f + ((lum - DARK_INK_FROM) * .6f).coerceIn(0f, .14f)
      return Ink(true, DARK_INK, DARK_SUB, argb(wash, 255, 250, 242), argb(.35f, 255, 255, 255))
    }
    // Brighter photo, stronger local scrim (mock: .2 + clamp((lum - .25) * .9, 0, .38)).
    val scrim = (if (night) .22f else .2f) + ((lum - .25f) * .9f).coerceIn(0f, .38f)
    val shadow = (.38f + busy * .6f).coerceAtMost(.6f)
    return Ink(false, if (night) NIGHT_INK else DAY_INK, if (night) NIGHT_SUB else DAY_SUB,
        argb(scrim, 12, 8, 6), argb(shadow, 0, 0, 0))
  }

  /**
   * Average brightness under a viewport [zone], given where the photo sits on screen. A
   * contained photo leaves most zones over its backdrop, which is the same photo covered,
   * blurred and dimmed by [backdropBrightness]; those parts are sampled from the cover pose.
   */
  fun zone(
      analysis: PhotoAnalysis, zone: Zone, pose: Transform, contained: Boolean,
      imageWidth: Int, imageHeight: Int, viewportWidth: Int, viewportHeight: Int,
      backdropBrightness: Float = AMBIENT_BRIGHTNESS,
  ): PhotoAnalysis.Zone {
    if (imageWidth <= 0 || imageHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0 ||
        pose.scale <= 0f) return PhotoAnalysis.Zone(.4f, 0f)
    fun sample(t: Transform): PhotoAnalysis.Zone {
      val w = imageWidth * t.scale
      val h = imageHeight * t.scale
      return analysis.region((zone.left * viewportWidth - t.x) / w, (zone.top * viewportHeight - t.y) / h,
          (zone.right * viewportWidth - t.x) / w, (zone.bottom * viewportHeight - t.y) / h)
    }
    if (!contained) return sample(pose)
    val cover = maxOf(viewportWidth.toFloat() / imageWidth, viewportHeight.toFloat() / imageHeight)
    val backdrop = sample(Transform(cover, (viewportWidth - imageWidth * cover) / 2f,
        (viewportHeight - imageHeight * cover) / 2f))
    // Blur removes texture; dimming scales lightness roughly linearly at these levels.
    return PhotoAnalysis.Zone(backdrop.luminance * backdropBrightness, backdrop.texture * .25f)
  }

  fun appearance(
      analysis: PhotoAnalysis, pose: Transform, contained: Boolean,
      imageWidth: Int, imageHeight: Int, viewportWidth: Int, viewportHeight: Int, night: Boolean,
  ): Appearance {
    fun ink(z: Zone) = zone(analysis, z, pose, contained, imageWidth, imageHeight,
        viewportWidth, viewportHeight).let { inkFor(it.luminance, it.texture, night) }
    return Appearance(accent(analysis.dominant, night), ink(Zone.CLOCK), ink(Zone.CAPTION), ink(Zone.PANEL))
  }

  /** Matches the ambient backdrop's brightness matrix. */
  const val AMBIENT_BRIGHTNESS = .62f

  fun lerpArgb(a: Int, b: Int, f: Float): Int {
    val t = if (f.isFinite()) f.coerceIn(0f, 1f) else 1f
    fun ch(shift: Int) = ((a shr shift and 255) + ((b shr shift and 255) - (a shr shift and 255)) * t).roundToInt()
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
  }

  private fun argb(alpha: Float, r: Int, g: Int, b: Int): Int =
      ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (r shl 16) or (g shl 8) or b

  fun hslToArgb(h: Float, s: Float, l: Float): Int {
    val a = s * minOf(l, 1f - l)
    fun f(n: Int): Int {
      val k = (n + h / 30f) % 12f
      return ((l - a * maxOf(-1f, minOf(k - 3f, 9f - k, 1f))) * 255f).roundToInt().coerceIn(0, 255)
    }
    return (255 shl 24) or (f(0) shl 16) or (f(8) shl 8) or f(4)
  }
}
