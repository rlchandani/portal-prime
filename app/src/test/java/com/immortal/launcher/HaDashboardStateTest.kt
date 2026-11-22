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

class HaDashboardStateTest {
  private val house = haHouse(haArea("living", haDetail("light.a")))
  private val ready = HaDashboardState.Ready(house, fetchedAtMs = 1_000)

  @Test fun `a good fetch is Ready, an empty house is No rooms`() {
    assertEquals(ready, HaDashboardState.after(HaDashboardState.Loading, HaResult.Ok(house), 1_000))
    assertEquals(HaDashboardState.NoRooms, HaDashboardState.after(HaDashboardState.Loading, HaResult.Ok(haHouse()), 1_000))
  }

  @Test fun `a refused token is Unauthorized even with last-known data`() {
    assertEquals(HaDashboardState.Unauthorized, HaDashboardState.after(ready, HaResult.Unauthorized, 5_000))
  }

  @Test fun `no answer after a good fetch keeps last known as Offline`() {
    val offline = HaDashboardState.after(ready, HaResult.Unreachable, 300_000)
    assertEquals(HaDashboardState.Offline(house, 1_000), offline)
    assertEquals(house, offline.house)
    assertEquals(4L, offline.offlineMinutes(241_000))
    // Still offline: the age keeps counting from the last good fetch.
    assertEquals(offline, HaDashboardState.after(offline, HaResult.Failed(502), 900_000))
    assertNull(ready.offlineMinutes(9_000_000))
  }

  @Test fun `no answer on first load is Unreachable with the code when HA answered`() {
    assertEquals(HaDashboardState.Unreachable(null), HaDashboardState.after(HaDashboardState.Loading, HaResult.Unreachable, 0))
    assertEquals(HaDashboardState.Unreachable(500), HaDashboardState.after(HaDashboardState.Loading, HaResult.Failed(500), 0))
    assertEquals(HaDashboardState.NotConfigured, HaDashboardState.after(ready, HaResult.NotConfigured, 0))
  }

  @Test fun `recovering from offline is Ready again`() {
    val offline = HaDashboardState.Offline(house, 1_000)
    assertEquals(HaDashboardState.Ready(house, 60_000), HaDashboardState.after(offline, HaResult.Ok(house), 60_000))
    assertNull(HaDashboardState.Loading.house)
  }
}
