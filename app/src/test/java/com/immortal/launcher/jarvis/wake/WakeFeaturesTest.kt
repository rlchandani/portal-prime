/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Feature quantisation against microWakeWord's reference (pymicro-wakeword 2.5.0) for the bundled model's input
 * tensor: scale 0.10196079 (26/255), zero point -128, stride 3.
 */
class WakeFeaturesTest {
  private val features = WakeFeatures(scale = 0.10196079f, zeroPoint = -128, stride = 3)

  @Test fun quantisesLikeTheReference() {
    // round(v × 0.0390625 / 0.10196079 − 128), clamped to int8: 0 → −128, 667+ saturates.
    val frame = IntArray(40) { listOf(0, 1, 13, 100, 333, 666, 667, 700, 65535)[it % 9] }
    val into = ByteArray(3 * 40)
    features.quantize(frame, 1, into)
    val expected = byteArrayOf(-128, -128, -123, -90, 0, 127, 127, 127, 127)
    assertArrayEquals(expected, into.copyOfRange(40, 49))
    assertArrayEquals(ByteArray(40), into.copyOfRange(0, 40))
  }
}
