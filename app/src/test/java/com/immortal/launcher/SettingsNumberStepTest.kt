/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class SettingsNumberStepTest {
  @Test fun fiveMinuteStepsReachBothUnevenEndpoints() {
    assertEquals(1, stepSettingNumber(5, -5, 1, 60, false))
    assertEquals(60, stepSettingNumber(56, 5, 1, 60, false))
    assertEquals(1, stepSettingNumber(1, -5, 1, 60, false))
    assertEquals(60, stepSettingNumber(60, 5, 1, 60, false))
  }
  @Test fun ordinaryStepsAndWrappingRetainTheirExistingBehavior() {
    assertEquals(25, stepSettingNumber(20, 5, 1, 60, false))
    assertEquals(0, stepSettingNumber(23, 1, 0, 23, true))
    assertEquals(23, stepSettingNumber(0, -1, 0, 23, true))
  }
  @Test fun integerArithmeticCannotOverflowAtTheBounds() {
    assertEquals(Int.MAX_VALUE, stepSettingNumber(Int.MAX_VALUE, 5, 1, Int.MAX_VALUE, false))
    assertEquals(Int.MIN_VALUE, stepSettingNumber(Int.MIN_VALUE, -5, Int.MIN_VALUE, 60, false))
  }
}
