/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherTabPrefetchTest {
  private val home = ApprovedLauncherTab.HOME
  private val apps = ApprovedLauncherTab.APPS
  private val tools = ApprovedLauncherTab.TOOLS

  @Test fun prefetchesInOrderAndSkipsVisitedTabs() {
    val order = LauncherTabPrefetch.HOME_TABS
    assertEquals(apps, LauncherTabPrefetch.next(order, setOf(home)))
    assertEquals(tools, LauncherTabPrefetch.next(order, setOf(home, apps)))
    assertEquals(apps, LauncherTabPrefetch.next(order, setOf(tools)))
  }

  @Test fun stopsOnceEveryTabIsComposed() {
    assertNull(LauncherTabPrefetch.next(LauncherTabPrefetch.HOME_TABS, setOf(home, apps, tools)))
    assertNull(LauncherTabPrefetch.next(emptyList(), setOf(home)))
  }

  @Test fun homeNeverPrefetchesItself() {
    assertTrue(home !in LauncherTabPrefetch.HOME_TABS)
  }
}
