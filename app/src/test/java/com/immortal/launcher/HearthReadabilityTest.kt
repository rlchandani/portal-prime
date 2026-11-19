/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.HearthReadability.Zone
import com.immortal.launcher.SlideshowGeometry.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HearthReadabilityTest {
  private fun argb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b
  private fun alpha(c: Int) = (c ushr 24) / 255f

  @Test fun accentKeepsThePhotoHueInAReadableBand() {
    val hsl = FloatArray(3)
    val blue = HearthReadability.accent(argb(20, 60, 200), night = false)
    PhotoAnalysis.rgbToHsl(blue shr 16 and 255, blue shr 8 and 255, blue and 255, hsl)
    val source = FloatArray(3).also { PhotoAnalysis.rgbToHsl(20, 60, 200, it) }
    assertEquals(source[0], hsl[0], 2f)
    assertEquals(.76f, hsl[2], .02f)
    val night = HearthReadability.accent(argb(20, 60, 200), night = true)
    PhotoAnalysis.rgbToHsl(night shr 16 and 255, night shr 8 and 255, night and 255, hsl)
    assertEquals(.66f, hsl[2], .02f)
  }

  @Test fun monochromePhotosKeepTheEmber() {
    assertEquals(HearthReadability.EMBER, HearthReadability.accent(null, night = false))
  }

  @Test fun brighterZonesGetAStrongerScrimUpToTheCap() {
    val dim = HearthReadability.inkFor(.1f, .5f, night = false)
    val mid = HearthReadability.inkFor(.5f, .5f, night = false)
    val bright = HearthReadability.inkFor(.95f, .5f, night = false)
    assertFalse(dim.dark || mid.dark || bright.dark)
    assertEquals(.2f, alpha(dim.scrim), .01f)
    assertEquals(.2f + (.5f - .25f) * .9f, alpha(mid.scrim), .01f)
    assertEquals(.58f, alpha(bright.scrim), .01f)
  }

  @Test fun brightCalmZonesSwitchToDarkInkWithALightWash() {
    val ink = HearthReadability.inkFor(.85f, .05f, night = false)
    assertTrue(ink.dark)
    assertTrue((ink.ink shr 16 and 255) < 64)
    assertTrue((ink.scrim shr 16 and 255) > 240)
  }

  @Test fun nightNeverUsesDarkInk() {
    assertFalse(HearthReadability.inkFor(.95f, 0f, night = true).dark)
    assertEquals(HearthReadability.NIGHT_INK, HearthReadability.inkFor(.3f, 0f, night = true).ink)
  }

  @Test fun busyZonesGetAHeavierShadow() {
    val calm = HearthReadability.inkFor(.4f, 0f, night = false)
    val busy = HearthReadability.inkFor(.4f, .5f, night = false)
    assertTrue(alpha(busy.shadow) > alpha(calm.shadow))
  }

  @Test fun invalidInputIsTreatedAsAMidZone() {
    val ink = HearthReadability.inkFor(Float.NaN, Float.NaN, night = false)
    assertEquals(HearthReadability.inkFor(.4f, 0f, night = false), ink)
  }

  @Test fun zoneSamplesThePhotoUnderACoverPose() {
    // Bottom half bright, top half dark; a 1:1 cover pose maps the clock zone to the bottom.
    val px = IntArray(64 * 64) { if (it / 64 >= 32) argb(255, 255, 255) else argb(0, 0, 0) }
    val a = PhotoAnalysis.of(px, 64, 64)
    val z = HearthReadability.zone(a, Zone.CLOCK, Transform(1f, 0f, 0f), false, 1000, 1000, 1000, 1000)
    assertTrue(z.luminance > .6f)
    val panel = HearthReadability.zone(a, Zone.PANEL, Transform(1f, 0f, 0f), false, 1000, 1000, 1000, 1000)
    assertTrue(panel.luminance < .2f)
  }

  @Test fun containedPhotosSampleTheirDimmedBackdrop() {
    val a = PhotoAnalysis.of(IntArray(32 * 32) { argb(255, 255, 255) }, 32, 32)
    val z = HearthReadability.zone(a, Zone.CLOCK, Transform(.3f, 100f, 0f), true, 1000, 1500, 2160, 1440)
    assertEquals(HearthReadability.AMBIENT_BRIGHTNESS, z.luminance, .02f)
    assertTrue(z.texture < .01f)
  }

  @Test fun appearanceMixesEndpointsExactly() {
    val a = HearthReadability.Appearance.neutral(false)
    val b = HearthReadability.Appearance(argb(10, 20, 30), HearthReadability.inkFor(.9f, 0f, false),
        HearthReadability.inkFor(.9f, 0f, false), HearthReadability.inkFor(.9f, 0f, false))
    assertEquals(a, a.mix(b, 0f))
    assertEquals(b.accent, a.mix(b, 1f).accent)
    assertEquals(b.clock.ink, a.mix(b, 1f).clock.ink)
  }

  @Test fun lerpArgbInterpolatesEveryChannel() {
    assertEquals(0x80808080.toInt(), HearthReadability.lerpArgb(0, 0xFFFFFFFF.toInt(), .5f))
    assertEquals(0xFFFFFFFF.toInt(), HearthReadability.lerpArgb(0, 0xFFFFFFFF.toInt(), Float.NaN))
  }
}
