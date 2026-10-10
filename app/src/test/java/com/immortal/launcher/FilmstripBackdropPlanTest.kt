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
}
