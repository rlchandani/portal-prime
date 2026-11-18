/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicFloatPositionCodecTest {
  @Test fun encodesVersionedFractions() {
    assertEquals("v1:0.2500,1.0000", MusicFloatPositionCodec.encode(MusicFloatPosition(0.25f, 1f)))
  }

  @Test fun roundTrips() {
    val p = MusicFloatPosition(0.1234f, 0.9876f)
    assertEquals(p, MusicFloatPositionCodec.decode(MusicFloatPositionCodec.encode(p)))
  }

  @Test fun encodeAndDecodeClampToTheUnitRange() {
    assertEquals("v1:0.0000,1.0000", MusicFloatPositionCodec.encode(MusicFloatPosition(-2f, 7f)))
    assertEquals(MusicFloatPosition(0f, 1f), MusicFloatPositionCodec.decode("v1:-3,4"))
  }

  @Test fun rejectsMissingForeignOrBrokenValues() {
    listOf(null, "", "0.5,0.5", "v2:0.5,0.5", "v1:0.5", "v1:a,b", "v1:0.5,0.5,0.5", "v1:NaN,0.5", "v1:0.5,Infinity")
        .forEach { assertNull("$it", MusicFloatPositionCodec.decode(it)) }
  }
}
