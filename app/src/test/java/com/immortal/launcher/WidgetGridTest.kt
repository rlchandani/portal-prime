/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetGridTest {
  @Test fun sizeCycle_smallMediumLargeWrap() {
    assertEquals(2 to 1, HomeWidgetStore.nextWidgetSpan(1 to 1)) // small -> medium
    assertEquals(2 to 2, HomeWidgetStore.nextWidgetSpan(2 to 1)) // medium -> large
    assertEquals(1 to 1, HomeWidgetStore.nextWidgetSpan(2 to 2)) // large -> small
  }
}
