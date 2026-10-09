/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class EngineDerivativeSizingTest {
  @Test fun `tiers round upward without serving an undersized full-screen image`() {
    assertEquals(600,EngineScaledCache.derivativeEdge(320))
    assertEquals(1200,EngineScaledCache.derivativeEdge(900))
    assertEquals(1800,EngineScaledCache.derivativeEdge(1400))
    assertEquals(2400,EngineScaledCache.derivativeEdge(2200))
    assertEquals(2400,EngineScaledCache.derivativeEdge(Int.MAX_VALUE))
    assertEquals(2400,EngineScaledCache.derivativeEdge(-1))
  }
  @Test fun `variant identities stay distinct and default keys remain compatible`() {
    val full=EngineScaledCache.keyFor("a",123,456)
    assertEquals(full,EngineScaledCache.keyFor("a",123,456,2400))
    assertNotEquals(full,EngineScaledCache.keyFor("a",123,456,1200))
    assertNotEquals(EngineScaledCache.keyFor("a",123,456,600),EngineScaledCache.keyFor("a",123,456,1200))
  }
  @Test fun `huge square panoramas and camera headers keep intermediate pixels bounded`() {
    for ((w,h) in listOf(4000 to 3000,8192 to 8192,32768 to 1024,Int.MAX_VALUE to Int.MAX_VALUE)) {
      for (edge in listOf(600,1200,2400)) {
        val sample=EngineScaledCache.boundedSampleSize(w,h,edge)
        val sw=(w.toLong()+sample-1)/sample;val sh=(h.toLong()+sample-1)/sample
        assertTrue(sw*sh<=EngineScaledCache.MAX_SOURCE_PIXELS)
      }
    }
    assertEquals(1,EngineScaledCache.boundedSampleSize(4000,3000,2400))
    assertEquals(2,EngineScaledCache.boundedSampleSize(4000,3000,1200))
  }
}
