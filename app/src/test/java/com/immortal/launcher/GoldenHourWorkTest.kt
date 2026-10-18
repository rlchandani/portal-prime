/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class GoldenHourWorkTest {
  @Test fun `disabled feature allocates no request`() {
    assertNull(GoldenHourWork().begin { false })
  }

  @Test fun `turning off rejects a fetched response even if enabled again`() {
    val work = GoldenHourWork(); val token = work.begin { true }!!
    var called = 0
    work.cancel { called++ }
    assertFalse(work.isCurrent(token))
    assertFalse(work.commit(token, { true }) { called++ })
    assertEquals(1, called)
  }

  @Test fun `new request supersedes old queued and completed work`() {
    val work = GoldenHourWork(); val old = work.begin { true }!!; val fresh = work.begin { true }!!
    assertFalse(work.isCurrent(old)); assertTrue(work.isCurrent(fresh))
    var called = 0
    assertFalse(work.commit(old, { true }) { called++ })
    assertTrue(work.commit(fresh, { true }) { called++ })
    assertEquals(1, called)
  }

  @Test fun `current setting is checked again before alarm installation`() {
    val work = GoldenHourWork(); val token = work.begin { true }!!
    assertFalse(work.commit(token, { false }) { fail("Disabled request installed alarms") })
  }

  @Test fun `current enabled request installs its pair`() {
    val work = GoldenHourWork(); val token = work.begin { true }!!
    var installed = false
    assertTrue(work.commit(token, { true }) { installed = true })
    assertTrue(installed)
  }

  @Test fun `cancellation after installation wins without rearming between pair writes`() {
    val work = GoldenHourWork(); val token = work.begin { true }!!
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    val events = mutableListOf<String>()
    val install = Thread {
      work.commit(token, { true }) { entered.countDown(); release.await(2, TimeUnit.SECONDS); events.add("armed") }
    }
    install.start()
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      val cancel = Thread { work.cancel { events.add("cancelled") } }
      cancel.start(); release.countDown(); install.join(2000); cancel.join(2000)
      assertFalse(install.isAlive); assertFalse(cancel.isAlive)
      assertEquals(listOf("armed", "cancelled"), events)
      assertFalse(work.isCurrent(token))
    } finally { release.countDown(); install.join(2000) }
  }
  private val publicDays = listOf(Weather.SunTimes(100, 200, 0))

  @Test fun `startup and queued receiver reuse the completed solar lookup`() {
    val cache = GoldenHourSolarCache(); var loads = 0
    assertEquals(publicDays, cache.get(0) { loads++; publicDays })
    assertEquals(publicDays, cache.get(0) { loads++; fail("Receiver made a second lookup"); null })
    assertEquals(1, loads)
  }

  @Test fun `unavailable startup lookup is shared instead of delaying receiver again`() {
    val cache = GoldenHourSolarCache(); var loads = 0
    assertNull(cache.get(0) { loads++; null })
    assertNull(cache.get(0) { loads++; publicDays })
    assertEquals(1, loads)
  }

  @Test fun `failed startup lookup is shared instead of performing consecutive failures`() {
    val cache = GoldenHourSolarCache(); var loads = 0
    assertNull(cache.get(0) { loads++; error("Public simulated failure") })
    assertNull(cache.get(0) { loads++; publicDays })
    assertEquals(1, loads)
  }

  @Test fun `explicit location revision and expiry allow fresh solar data`() {
    var now = 0L; val cache = GoldenHourSolarCache { now }; var loads = 0
    cache.get(0) { loads++; publicDays }
    cache.get(1) { loads++; publicDays }
    now = 31_000
    cache.get(1) { loads++; publicDays }
    cache.clear(); cache.get(1) { loads++; publicDays }
    assertEquals(4, loads)
  }

  @Test fun `concurrent app and receiver share in flight geolocation and solar work`() {
    val cache = GoldenHourSolarCache(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
    val loads = java.util.concurrent.atomic.AtomicInteger()
    var first: List<Weather.SunTimes>? = null; var second: List<Weather.SunTimes>? = null
    val startup = Thread { first = cache.get(0) { loads.incrementAndGet(); entered.countDown(); release.await(2, TimeUnit.SECONDS); publicDays } }
    val receiver = Thread { second = cache.get(0) { loads.incrementAndGet(); publicDays } }
    startup.start()
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS)); receiver.start(); release.countDown()
      startup.join(2000); receiver.join(2000)
      assertFalse(startup.isAlive); assertFalse(receiver.isAlive)
      assertEquals(1, loads.get()); assertEquals(publicDays, first); assertEquals(publicDays, second)
    } finally { release.countDown(); startup.join(2000); if (receiver.isAlive) receiver.join(2000) }
  }

}
