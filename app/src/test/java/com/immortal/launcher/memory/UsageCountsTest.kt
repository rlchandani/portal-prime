/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFixtures.DAY
import com.immortal.launcher.memory.MemoryFixtures.NOW
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Silent usage counts for slice 2 (plan §3.6): per hour, per scope, 14-day retention, sealed JSON. */
class UsageCountsTest {
  private val utc = ZoneOffset.UTC
  private val house = MemoryScope.Household

  @Test fun countsOpensPerHourOfTheWeek() {
    val c = UsageCounts().record(house, "screen:music", NOW, utc).record(house, "screen:music", NOW + 60_000, utc)
        .record(house, "app:com.example", NOW, utc)
    val week = c.hourOfWeek(house, "screen:music")
    assertEquals(2, week[15]) // Monday 15:00
    assertEquals(2, week.sum())
    assertEquals(0, c.hourOfWeek(MemoryScope.Person("p1"), "screen:music").sum())
  }

  @Test fun keepsFourteenDaysOnly() {
    val old = UsageCounts().record(house, "screen:calls", NOW, utc)
    assertEquals(1, old.pruned(NOW + 13 * DAY, utc).buckets.size)
    assertTrue(old.pruned(NOW + 14 * DAY, utc).buckets.isEmpty())
  }

  @Test fun roundTripsAndDropsWhatItCannotRead() {
    val c = UsageCounts().record(house, "screen:music", NOW, utc).record(MemoryScope.Person("p1"), "jarvis", NOW, utc)
    assertEquals(c, UsageCounts.decode(c.encode()))
    assertEquals(UsageCounts(), UsageCounts.decode("garbage"))
    assertEquals(UsageCounts(), UsageCounts.decode("{\"v\":9,\"b\":[]}"))
    assertEquals(0, UsageCounts.decode("{\"v\":1,\"b\":[[\"nobody\",\"x\",1,1,1],[\"household\",\"Bad Surface!\",1,1,1]]}").buckets.size)
  }

  @Test fun forgettingAScopeRemovesOnlyItsCounts() {
    val c = UsageCounts().record(house, "jarvis", NOW, utc).record(MemoryScope.Person("p1"), "jarvis", NOW, utc)
    assertEquals(listOf("household"), c.without(MemoryScope.Person("p1")).buckets.map { it.scope })
  }

  @Test fun anInvalidSurfaceIsIgnored() =
      assertTrue(UsageCounts().record(house, "Has Spaces", NOW, utc).buckets.isEmpty())

  @Test fun surfacesAreNormalized() {
    assertEquals("app:com.example.app", MemoryUsage.surfaceForApp("com.Example.App"))
    assertEquals("screen:control_center", MemoryUsage.surfaceForScreen("control_center"))
  }
}
