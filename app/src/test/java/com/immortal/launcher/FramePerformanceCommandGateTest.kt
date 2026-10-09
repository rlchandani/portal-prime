/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class FramePerformanceCommandGateTest {
  @Test fun cancelledQueuedCommandNeverBegins() {
    val gate=FramePerformanceCommandGate()
    assertTrue(gate.cancelBeforeBegin());assertFalse(gate.begin())
  }
  @Test fun begunCommandCannotBeReportedAsCancelled() {
    val gate=FramePerformanceCommandGate()
    assertTrue(gate.begin());assertFalse(gate.cancelBeforeBegin())
  }
  @Test fun managerDisplayBudgetWorksBeforeDecorAttachment() {
    assertEquals(11_111_111L,FramePerformanceBudget.nanos(null,90.0))
    assertEquals(8_333_333L,FramePerformanceBudget.nanos(120.0,60.0))
  }
  @Test fun invalidDisplayRatesFallBackWithoutNaNOrZeroDivision() {
    assertEquals(16_666_667L,FramePerformanceBudget.nanos(Double.NaN,0.0))
    assertEquals(16_666_667L,FramePerformanceBudget.nanos(Double.POSITIVE_INFINITY,null))
    assertEquals(4_166_667L,FramePerformanceBudget.nanos(null,240.0))
  }
}
