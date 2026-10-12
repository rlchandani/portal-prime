/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class SingleFlightValueCacheTest {
  @Test fun simultaneousReaders_shareOneLoadAndTheSameResult() {
    val cache = SingleFlightValueCache<String, Any>(30_000, 4)
    val readers = Executors.newFixedThreadPool(8)
    val entered = CountDownLatch(8)
    val firstLoad = CountDownLatch(1)
    val release = CountDownLatch(1)
    val loads = AtomicInteger()
    val value = Any()
    try {
      val futures = (0 until 8).map {
        readers.submit<Any> {
          entered.countDown()
          cache.get("same") {
            loads.incrementAndGet(); firstLoad.countDown()
            check(release.await(3, TimeUnit.SECONDS)); value
          }
        }
      }
      assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(firstLoad.await(2, TimeUnit.SECONDS))
      release.countDown()
      futures.forEach { assertSame(value, it.get(3, TimeUnit.SECONDS)) }
      println("weatherCurrentWork readers=8 loads=${loads.get()}")
      assertEquals(1, loads.get())
    } finally { release.countDown(); readers.shutdownNow() }
  }

  @Test fun repeatedReaders_reuseSuccessUntilExpiry() {
    val clock = AtomicLong(100)
    val cache = SingleFlightValueCache<String, String>(30, 4, clock::get)
    var loads = 0
    repeat(20) { assertEquals("value1", cache.get("same") { "value${++loads}" }) }
    clock.set(129); assertEquals("value1", cache.get("same") { "value${++loads}" })
    clock.set(130); assertEquals("value2", cache.get("same") { "value${++loads}" })
    assertEquals(2, loads)
  }

  @Test fun expiryStartsWhenLoadingCompletes() {
    val clock = AtomicLong(0)
    val cache = SingleFlightValueCache<String, String>(10, 4, clock::get)
    var loads = 0
    assertEquals("value1", cache.get("same") { clock.set(100); "value${++loads}" })
    clock.set(109); assertEquals("value1", cache.get("same") { "value${++loads}" })
    clock.set(110); assertEquals("value2", cache.get("same") { "value${++loads}" })
  }

  @Test fun unrelatedKeys_doNotWaitForAStalledLoader() {
    val cache = SingleFlightValueCache<String, String>(30_000, 4)
    val readers = Executors.newFixedThreadPool(2)
    val started = CountDownLatch(1); val release = CountDownLatch(1)
    try {
      val first = readers.submit<String> { cache.get("fahrenheit") { started.countDown(); check(release.await(3, TimeUnit.SECONDS)); "F" } }
      assertTrue(started.await(2, TimeUnit.SECONDS))
      assertEquals("C", readers.submit<String> { cache.get("celsius") { "C" } }.get(1, TimeUnit.SECONDS))
      release.countDown(); assertEquals("F", first.get(2, TimeUnit.SECONDS))
    } finally { release.countDown(); readers.shutdownNow() }
  }

  @Test fun failedLoads_areNotCachedAndPreserveTheOriginalFailure() {
    val cache = SingleFlightValueCache<String, String>(30_000, 4)
    val failure = IllegalStateException("fixture failure")
    try { cache.get("same") { throw failure }; fail("failure expected") }
    catch (error: IllegalStateException) { assertSame(failure, error) }
    assertEquals("recovered", cache.get("same") { "recovered" })
  }

  @Test fun clearWhileLoading_preventsOldWorkFromReplacingTheNewValue() {
    val cache = SingleFlightValueCache<String, String>(30_000, 4)
    val readers = Executors.newSingleThreadExecutor()
    val started = CountDownLatch(1); val release = CountDownLatch(1)
    try {
      val old = readers.submit<String> { cache.get("same") { started.countDown(); check(release.await(3, TimeUnit.SECONDS)); "old" } }
      assertTrue(started.await(2, TimeUnit.SECONDS)); cache.clear()
      assertEquals("new", cache.get("same") { "new" })
      release.countDown(); assertEquals("old", old.get(2, TimeUnit.SECONDS))
      assertEquals("new", cache.get("same") { "unexpected reload" })
    } finally { release.countDown(); readers.shutdownNow() }
  }

  @Test fun completedValues_areBoundedAndEvictedByRecentUse() {
    val cache = SingleFlightValueCache<String, String>(30_000, 2)
    var loads = 0
    fun read(key: String) = cache.get(key) { loads++; key }
    assertEquals("a", read("a")); assertEquals("b", read("b")); assertEquals("a", read("a"))
    assertEquals("c", read("c")); assertEquals("b", read("b")); assertEquals(4, loads)
  }

  @Test fun backwardsClock_doesNotExtendCachedFreshness() {
    val clock = AtomicLong(100)
    val cache = SingleFlightValueCache<String, String>(30, 4, clock::get)
    assertEquals("first", cache.get("same") { "first" })
    clock.set(50); assertEquals("second", cache.get("same") { "second" })
  }

  @Test fun invalidBounds_areRejected() {
    for ((ttl, size) in listOf(0L to 1, -1L to 1, 1L to 0)) {
      try { SingleFlightValueCache<String, String>(ttl, size); fail("invalid bounds") }
      catch (_: IllegalArgumentException) {}
    }
  }

  @Test fun distinctKeys_doNotMixLocationsOrTemperatureUnits() {
    val cache = SingleFlightValueCache<String, String>(30_000, 4)
    assertEquals("F", cache.get("1,2,fahrenheit") { "F" })
    assertEquals("C", cache.get("1,2,celsius") { "C" })
    assertEquals("Other", cache.get("3,4,fahrenheit") { "Other" })
    assertEquals("F", cache.get("1,2,fahrenheit") { "wrong" })
  }

  @Test fun currentWeatherKey_keepsCoordinatesAndTemperatureUnitDistinct() {
    val fahrenheit = Weather.currentUrl(1.0, 2.0, true)
    assertTrue(fahrenheit.endsWith("temperature_unit=fahrenheit"))
    assertTrue(Weather.currentUrl(1.0, 2.0, false).endsWith("temperature_unit=celsius"))
    assertNotEquals(fahrenheit, Weather.currentUrl(1.0, 2.0, false))
    assertNotEquals(fahrenheit, Weather.currentUrl(3.0, 4.0, true))
  }

  @Test fun currentWeatherParser_preservesRoundedTemperatureAndWeatherCode() {
    val value = Weather.parseCurrent("""{"current":{"temperature_2m":72.6,"weather_code":2}}""")
    assertEquals(73, value.temp)
    assertEquals(2, value.code)
  }

  @Test fun invalidCurrentPayload_isNotCachedAndValidRecoveryIsShared() {
    val cache = SingleFlightValueCache<String, Weather.Conditions>(30_000, 4)
    val key = Weather.currentUrl(1.0, 2.0, true)
    var loads = 0
    try {
      cache.get(key) { loads++; Weather.parseCurrent("""{"current":{"weather_code":2}}""") }
      fail("invalid current payload")
    } catch (_: org.json.JSONException) {}
    val good = cache.get(key) { loads++; Weather.parseCurrent("""{"current":{"temperature_2m":17.4,"weather_code":3}}""") }
    assertEquals(17, good.temp)
    assertEquals(3, good.code)
    assertSame(good, cache.get(key) { error("must reuse valid parse") })
    assertEquals(2, loads)
  }


  @Test fun interruptedWaiter_preservesItsFlagWhileTheOwnerCompletesAndCaches() {
    val cache = SingleFlightValueCache<String, String>(30_000, 4)
    val ownerPool = Executors.newSingleThreadExecutor()
    val ownerStarted = CountDownLatch(1); val release = CountDownLatch(1)
    val waiterStarted = CountDownLatch(1); val waiterDone = CountDownLatch(1)
    val caught = AtomicBoolean(); val interrupted = AtomicBoolean()
    val waiter = Thread {
      waiterStarted.countDown()
      try { cache.get("same") { error("must join owner") } }
      catch (_: InterruptedException) {
        caught.set(true); interrupted.set(Thread.currentThread().isInterrupted)
      } finally { Thread.interrupted(); waiterDone.countDown() }
    }
    try {
      val owner = ownerPool.submit<String> { cache.get("same") { ownerStarted.countDown(); check(release.await(3, TimeUnit.SECONDS)); "value" } }
      assertTrue(ownerStarted.await(2, TimeUnit.SECONDS))
      waiter.start(); assertTrue(waiterStarted.await(1, TimeUnit.SECONDS)); waiter.interrupt()
      assertTrue(waiterDone.await(1, TimeUnit.SECONDS)); release.countDown()
      assertEquals("value", owner.get(2, TimeUnit.SECONDS))
      assertEquals("value", cache.get("same") { error("must keep owner's result") })
      assertTrue(caught.get()); assertTrue("interrupt flag must remain visible to the caller", interrupted.get())
    } finally { release.countDown(); waiter.interrupt(); waiter.join(1000); ownerPool.shutdownNow() }
  }

}
