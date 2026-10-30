/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class FilmstripBackdropPlanTest {
  @Test fun `portrait and landscape retain viewport aspect within one texture pixel`() {
    for ((width,height) in listOf(2160 to 1440,1440 to 2160,800 to 1280,1 to 32768)) {
      val (w,h) = FilmstripBackdrop.size(width,height)
      assertEquals(512,maxOf(w,h)); assertTrue(minOf(w,h)>0)
      assertTrue(kotlin.math.abs(w*height.toDouble()/width-h)<=1.0 || minOf(w,h)==1)
    }
  }
  @Test fun `rotation changes the crop target rather than clipping a square twice`() {
    val landscape = FilmstripBackdrop.size(2160,1440)
    val portrait = FilmstripBackdrop.size(1440,2160)
    assertEquals(landscape.first,portrait.second); assertEquals(landscape.second,portrait.first)
    assertTrue(portrait.second>portrait.first)
  }

  @Test fun `2160 by 1440 viewport at sample density keeps the existing blur strength`() {
    val plan = FilmstripBackdrop.plan(2160,1440,1.5f)
    assertEquals(512,plan.width); assertEquals(341,plan.height)
    assertEquals(1,plan.passes); assertEquals(16.277779f,plan.radius,.0001f)
  }

  @Test fun `gaussian passes retain the legacy variance for even and odd pass counts`() {
    val even = FilmstripBackdrop.plan(512,512,1f)
    assertEquals(4,even.passes); assertEquals(23.5f,even.radius,.0001f)
    val odd = FilmstripBackdrop.plan(512,512,.75f)
    assertEquals(3,odd.passes); assertEquals(20.150635f,odd.radius,.0001f)
    val two = FilmstripBackdrop.plan(512,512,.65f)
    assertEquals(2,two.passes); assertEquals(21.48097f,two.radius,.0001f)
  }

  @Test fun `scratch dimensions and intrinsic radius stay inside their admission bounds`() {
    for ((width,height) in listOf(1 to 1,512 to 512,2160 to 1440,1440 to 2160,32768 to 1)) {
      for (density in listOf(.01f,.5f,1f,1.5f,4f,10f)) {
        val plan = FilmstripBackdrop.plan(width,height,density)
        assertTrue(plan.width in 1..512); assertTrue(plan.height in 1..512)
        assertTrue(plan.passes in 1..8)
        assertTrue(plan.radius.isFinite() && plan.radius > 0f && plan.radius <= 25f)
        assertTrue(3L*plan.width*plan.height*4 <= 3L*1024*1024)
      }
    }
  }

  @Test fun `invalid viewports and nonfinite densities fail before allocating scratch`() {
    for ((width,height) in listOf(0 to 1440,2160 to 0,-1 to 1,1 to -1)) {
      assertThrows(IllegalArgumentException::class.java) { FilmstripBackdrop.plan(width,height,1f) }
    }
    for (density in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY)) {
      assertThrows(IllegalArgumentException::class.java) { FilmstripBackdrop.plan(2160,1440,density) }
    }
  }
}
