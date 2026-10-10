/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class InstalledPackageVersionsTest {
  private class Fixture {
    val app = mock(Context::class.java)
    val caller = mock(Context::class.java)
    var now = 1_000L
    var registers = 0
    var scans = 0
    var registerSucceeds = true
    var queryFails = false
    var callback: (() -> Unit)? = null
    var packages: Map<String, Long> = mapOf("one" to 7L)
    val queried = mutableListOf<Set<String>>()
    val inventory = InstalledVersionInventory({ now }, { context, invalidation ->
      assertSame(app, context)
      registers++
      callback = invalidation
      registerSucceeds
    }, { context, requested ->
      assertSame(app, context)
      scans++
      queried += requested.toSet()
      if (queryFails) throw IllegalStateException("generated query failure")
      packages.filterKeys { it in requested }
    })
    init {
      `when`(caller.applicationContext).thenReturn(app)
      `when`(app.applicationContext).thenReturn(app)
    }
  }

  @Test fun oldExpiryObservationCannotDiscardNewlyPublishedSnapshot() {
    val cache = HomePresentationInventoryCache<Long>()
    val old = cache.load { listOf(1L) }
    cache.invalidate()
    val fresh = cache.load { listOf(2L) }
    assertFalse(cache.invalidateIfCurrent(old))
    assertSame(fresh, cache.peek())
    assertTrue(cache.invalidateIfCurrent(fresh))
    assertNull(cache.peek())
  }

  @Test fun registerIsLazyAndDoesNotScanOrRetainActivity() {
    val f = Fixture()
    assertTrue(f.inventory.register(f.caller))
    assertTrue(f.inventory.register(f.caller))
    assertEquals(1, f.registers)
    assertEquals(0, f.scans)
  }
  @Test fun warmReadsShareOneImmutableSnapshotAndCacheAbsence() {
    val f = Fixture()
    val first = requireNotNull(f.inventory.snapshot(f.caller, setOf("one", "unknown")))
    assertEquals(7L, first.versionCode("one"))
    assertNull(first.versionCode("unknown"))
    assertSame(first, f.inventory.snapshot(f.caller, setOf("one", "unknown")))
    assertEquals(1, f.scans)
    assertEquals(1, f.registers)
  }
  @Test fun snapshotOwnsMapCopy() {
    val f = Fixture()
    val mutable = mutableMapOf("one" to 7L)
    f.packages = mutable
    val first = requireNotNull(f.inventory.snapshot(f.caller, setOf("one")))
    mutable["one"] = 99L
    assertEquals(7L, first.versionCode("one"))
  }
  @Test fun loadTimestampIsAfterScanAndAgeUsesMonotonicClock() {
    val f = Fixture()
    val first = requireNotNull(f.inventory.snapshot(f.caller, setOf("one")))
    assertEquals(f.now, first.loadedElapsedMillis)
    f.now += 4_999
    assertSame(first, f.inventory.snapshot(f.caller, setOf("one")))
    f.now++
    f.packages = mapOf("one" to 8L)
    val next = requireNotNull(f.inventory.snapshot(f.caller, setOf("one")))
    assertNotSame(first, next)
    assertEquals(8L, next.versionCode("one"))
    assertEquals(2, f.scans)
  }
  @Test fun slowScanAgeStartsAtCompletionAndNotAtRequest() {
    val app = mock(Context::class.java)
    `when`(app.applicationContext).thenReturn(app)
    var now = 1_000L
    var scans = 0
    val cache = InstalledVersionInventory({ now }, { _, _ -> true }, { _, _ ->
      scans++;now += 800L;mapOf("one" to 5L)
    })
    val first = requireNotNull(cache.snapshot(app, setOf("one")))
    assertEquals(1_800L, first.loadedElapsedMillis)
    now = 6_799L
    assertSame(first, cache.snapshot(app, setOf("one")))
    assertEquals(1, scans)
    now++
    assertNotSame(first, cache.snapshot(app, setOf("one")))
    assertEquals(2, scans)
  }
  @Test fun expiredObserverCannotInvalidateAReplacementPublishedDuringItsClockRead() {
    val app = mock(Context::class.java)
    `when`(app.applicationContext).thenReturn(app)
    var now = 0L
    var armed = false
    var scans = 0
    var fresh: InstalledPackageVersionSnapshot? = null
    lateinit var cache: InstalledVersionInventory
    cache = InstalledVersionInventory({
      if (armed) {
        armed = false
        cache.invalidate()
        fresh = cache.snapshot(app, setOf("one"))
      }
      now
    }, { _, _ -> true }, { _, _ -> mapOf("one" to (++scans).toLong()) })
    requireNotNull(cache.snapshot(app, setOf("one")))
    now = 5_000L;armed = true
    val returned = requireNotNull(cache.snapshot(app, setOf("one")))
    assertSame(fresh, returned)
    assertEquals(2, scans)
    assertEquals(2L, returned.versionCode("one"))
  }
  @Test fun replacementThatExpiresBeforeDelayedObserverReturnsIsRefreshedAgain() {
    val app = mock(Context::class.java)
    `when`(app.applicationContext).thenReturn(app)
    var now = 0L
    var armed = false
    var scans = 0
    lateinit var cache: InstalledVersionInventory
    cache = InstalledVersionInventory({
      if (armed) {
        armed = false
        cache.invalidate()
        requireNotNull(cache.snapshot(app, setOf("one")))
        // Model a request descheduled while its replacement also reaches the age limit.
        now += 5_000L
      }
      now
    }, { _, _ -> true }, { _, _ -> mapOf("one" to (++scans).toLong()) })
    requireNotNull(cache.snapshot(app, setOf("one")))
    now = 5_000L;armed = true
    val returned = requireNotNull(cache.snapshot(app, setOf("one")))
    assertEquals(3L, returned.versionCode("one"))
    assertEquals(now, returned.loadedElapsedMillis)
    assertEquals(3, scans)
  }
  @Test fun subscriberExceptionIsUnavailableAndCanRecoverOnNextRead() {
    val app = mock(Context::class.java)
    `when`(app.applicationContext).thenReturn(app)
    var attempts = 0
    val cache = InstalledVersionInventory({ 1L }, { _, _ ->
      if (++attempts == 1) throw SecurityException("generated registration failure")
      true
    }, { _, _ -> mapOf("one" to 1L) })
    assertNull(cache.snapshot(app, setOf("one")))
    assertEquals(1L, requireNotNull(cache.snapshot(app, setOf("one"))).versionCode("one"))
    assertEquals(2, attempts)
  }

  @Test fun backwardsClockCannotKeepFutureSnapshotFresh() {
    val f = Fixture()
    requireNotNull(f.inventory.snapshot(f.caller, setOf("one")))
    f.now--
    f.packages = emptyMap()
    assertNull(requireNotNull(f.inventory.snapshot(f.caller, setOf("one"))).versionCode("one"))
    assertEquals(2, f.scans)
  }
  @Test fun packageEventsInvalidateWithoutScanningAndRecoverNegativeEntry() {
    val f = Fixture()
    val first = requireNotNull(f.inventory.snapshot(f.caller, setOf("one", "new")))
    assertNull(first.versionCode("new"))
    f.packages = mapOf("one" to 8L, "new" to 1L)
    requireNotNull(f.callback).invoke()
    assertEquals(1, f.scans)
    val installed = requireNotNull(f.inventory.snapshot(f.caller, setOf("one", "new")))
    assertEquals(1L, installed.versionCode("new"))
    assertEquals(8L, installed.versionCode("one"))
    f.packages = emptyMap()
    requireNotNull(f.callback).invoke()
    assertNull(requireNotNull(f.inventory.snapshot(f.caller, setOf("one", "new"))).versionCode("one"))
  }
  @Test fun ownedInstallInvalidationMakesNextReadFresh() {
    val f = Fixture()
    requireNotNull(f.inventory.snapshot(f.caller, setOf("one")))
    f.packages = mapOf("one" to 9L)
    f.inventory.invalidate()
    assertEquals(9L, requireNotNull(f.inventory.snapshot(f.caller, setOf("one"))).versionCode("one"))
  }
  @Test fun registrationFailureReturnsUnavailableWithoutPublishingOrScanning() {
    val f = Fixture()
    f.registerSucceeds = false
    assertNull(f.inventory.snapshot(f.caller, setOf("one")))
    assertNull(f.inventory.snapshot(f.caller, setOf("one")))
    assertEquals(0, f.scans)
    f.registerSucceeds = true
    assertEquals(7L, requireNotNull(f.inventory.snapshot(f.caller, setOf("one"))).versionCode("one"))
    assertEquals(1, f.scans)
  }
  @Test fun queryFailureIsNotCachedAsEmptyAndNextReadRetries() {
    val f = Fixture()
    f.queryFails = true
    assertNull(f.inventory.snapshot(f.caller, setOf("one")))
    f.queryFails = false
    assertEquals(7L, requireNotNull(f.inventory.snapshot(f.caller, setOf("one"))).versionCode("one"))
    assertEquals(2, f.scans)
  }
  @Test fun emptySuccessfulInventoryIsAValidCachedSnapshot() {
    val f = Fixture()
    f.packages = emptyMap()
    val first = requireNotNull(f.inventory.snapshot(f.caller, setOf("one")))
    assertNull(first.versionCode("one"))
    assertSame(first, f.inventory.snapshot(f.caller, setOf("one")))
    assertEquals(1, f.scans)
  }
  @Test fun contextWithoutApplicationCannotBecomeRetainedOwner() {
    val f = Fixture()
    assertFalse(f.inventory.register(mock(Context::class.java)))
    assertNull(f.inventory.snapshot(mock(Context::class.java), setOf("one")))
    assertEquals(0, f.registers)
    assertEquals(0, f.scans)
  }
  @Test fun invalidatedInFlightScanCannotRepublishOldPackageVersion() {
    val app = mock(Context::class.java)
    `when`(app.applicationContext).thenReturn(app)
    val began = CountDownLatch(1)
    val release = CountDownLatch(1)
    val scans = AtomicInteger()
    val result = AtomicReference<InstalledPackageVersionSnapshot?>()
    val failure = AtomicReference<Throwable?>()
    val cache = InstalledVersionInventory({ 100L }, { _, _ -> true }, { _, _ ->
      if (scans.incrementAndGet() == 1) {
        began.countDown()
        check(release.await(3, TimeUnit.SECONDS))
        mapOf("one" to 1L)
      } else mapOf("one" to 2L)
    })
    val worker = Thread {
      try { result.set(cache.snapshot(app, setOf("one"))) } catch (error: Throwable) { failure.set(error) }
    }.apply { start() }
    try {
      assertTrue(began.await(1, TimeUnit.SECONDS))
      cache.invalidate()
      release.countDown()
      worker.join(3_000)
      assertFalse(worker.isAlive)
      assertNull(failure.get())
      assertEquals(2L, requireNotNull(result.get()).versionCode("one"))
      assertSame(result.get(), cache.snapshot(app, setOf("one")))
      assertEquals(2, scans.get())
    } finally { release.countDown(); worker.join(3_000) }
  }
  @Test fun concurrentColdReadsShareOneScan() {
    val app = mock(Context::class.java)
    `when`(app.applicationContext).thenReturn(app)
    val began = CountDownLatch(1)
    val release = CountDownLatch(1)
    val scans = AtomicInteger()
    val first = AtomicReference<InstalledPackageVersionSnapshot?>()
    val second = AtomicReference<InstalledPackageVersionSnapshot?>()
    val cache = InstalledVersionInventory({ 100L }, { _, _ -> true }, { _, _ ->
      scans.incrementAndGet(); began.countDown(); check(release.await(3, TimeUnit.SECONDS))
      mapOf("one" to 1L)
    })
    val a = Thread { first.set(cache.snapshot(app, setOf("one"))) }.apply { start() }
    val b = Thread { second.set(cache.snapshot(app, setOf("one"))) }.apply { start() }
    try {
      assertTrue(began.await(1, TimeUnit.SECONDS))
      release.countDown()
      a.join(3_000);b.join(3_000)
      assertFalse(a.isAlive);assertFalse(b.isAlive)
      assertEquals(1, scans.get())
      assertSame(requireNotNull(first.get()), second.get())
    } finally { release.countDown();a.join(3_000);b.join(3_000) }
  }

  @Test fun newCoverageQueriesTheCatalogUnionAndRetainsItAfterInvalidation() {
    val f = Fixture()
    f.packages = mapOf("one" to 7L, "two" to 8L, "unrequested" to 9L)
    val first = requireNotNull(f.inventory.snapshot(f.caller, setOf("one")))
    assertFalse(first.covers(setOf("two")))
    assertNull(first.versionCode("unrequested"))
    val extended = requireNotNull(f.inventory.snapshot(f.caller, setOf("two")))
    assertTrue(extended.covers(setOf("one", "two")))
    assertEquals(7L, extended.versionCode("one"))
    assertEquals(8L, extended.versionCode("two"))
    assertEquals(listOf(setOf("one"), setOf("one", "two")), f.queried)
    assertSame(extended, f.inventory.snapshot(f.caller, setOf("one")))
    f.inventory.invalidate()
    requireNotNull(f.inventory.snapshot(f.caller, setOf("one")))
    assertEquals(setOf("one", "two"), f.queried.last())
  }

  @Test fun requestedNegativeEntryHasCoverageAndDoesNotRescanUntilInvalidation() {
    val f = Fixture()
    val first = requireNotNull(f.inventory.snapshot(f.caller, setOf("missing")))
    assertTrue(first.covers(setOf("missing")))
    assertNull(first.versionCode("missing"))
    f.packages = mapOf("missing" to 4L)
    assertSame(first, f.inventory.snapshot(f.caller, setOf("missing")))
    assertEquals(1, f.scans)
    f.inventory.invalidate()
    assertEquals(4L, requireNotNull(f.inventory.snapshot(f.caller, setOf("missing"))).versionCode("missing"))
    assertEquals(2, f.scans)
  }

  @Test fun snapshotOwnsCoverageCopyIncludingAbsentPackages() {
    val requested = mutableSetOf("one", "missing")
    val snapshot = InstalledPackageVersionSnapshot(mapOf("one" to 1L), requested, 100L)
    requested.clear()
    requested += "later"
    assertTrue(snapshot.covers(setOf("one", "missing")))
    assertFalse(snapshot.covers(setOf("later")))
    assertNull(snapshot.versionCode("missing"))
  }

  @Test fun disjointRequestDuringScanInvalidatesOldScopeAndSharesTheUnionResult() {
    val app = mock(Context::class.java)
    `when`(app.applicationContext).thenReturn(app)
    val began = CountDownLatch(1)
    val release = CountDownLatch(1)
    val scans = AtomicInteger()
    val first = AtomicReference<InstalledPackageVersionSnapshot?>()
    val second = AtomicReference<InstalledPackageVersionSnapshot?>()
    val failure = AtomicReference<Throwable?>()
    val queried = java.util.concurrent.CopyOnWriteArrayList<Set<String>>()
    val cache = InstalledVersionInventory({ 100L }, { _, _ -> true }, { _, requested ->
      queried += requested.toSet()
      if (scans.incrementAndGet() == 1) {
        began.countDown()
        check(release.await(3, TimeUnit.SECONDS))
      }
      requested.associateWith { if (it == "one") 1L else 2L }
    })
    assertTrue(cache.register(app))
    val a = Thread {
      try { first.set(cache.snapshot(app, setOf("one"))) }
      catch (error: Throwable) { failure.compareAndSet(null, error) }
    }
    val b = Thread {
      try { second.set(cache.snapshot(app, setOf("two"))) }
      catch (error: Throwable) { failure.compareAndSet(null, error) }
    }
    a.start()
    try {
      assertTrue(began.await(1, TimeUnit.SECONDS))
      b.start()
      // The scan holds only the flight lock; b admits its scope before waiting there.
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
      while (b.state != Thread.State.BLOCKED && b.isAlive && System.nanoTime() < deadline) Thread.yield()
      assertEquals(Thread.State.BLOCKED, b.state)
      release.countDown()
      a.join(3_000); b.join(3_000)
      assertFalse(a.isAlive); assertFalse(b.isAlive)
      assertNull(failure.get())
      val result = requireNotNull(first.get())
      assertTrue(result.covers(setOf("one", "two")))
      assertTrue(requireNotNull(second.get()).covers(setOf("two")))
      assertSame(result, second.get())
      assertEquals(listOf(setOf("one"), setOf("one", "two")), queried.toList())
      assertEquals(2, scans.get())
    } finally { release.countDown(); a.join(3_000); if (b.state != Thread.State.NEW) b.join(3_000) }
  }

  @Test fun packageQueryReadsOnlyRequestedPackagesAndPreservesLongVersions() {
    val app = mock(Context::class.java)
    val pm = mock(PackageManager::class.java)
    val info = mock(PackageInfo::class.java)
    `when`(app.packageManager).thenReturn(pm)
    `when`(pm.getPackageInfo("one", 0)).thenReturn(info)
    `when`(info.longVersionCode).thenReturn(4_294_967_301L)
    `when`(pm.getPackageInfo("missing", 0)).thenThrow(PackageManager.NameNotFoundException("missing"))
    assertEquals(mapOf("one" to 4_294_967_301L), queryInstalledPackageVersions(app, setOf("one", "missing"), 29))
    verify(pm).getPackageInfo("one", 0)
    verify(pm).getPackageInfo("missing", 0)
    verify(pm, never()).getInstalledPackages(anyInt())
    verifyNoMoreInteractions(pm)
  }

  @Suppress("DEPRECATION")
  @Test fun packageQueryUsesLegacyVersionBelowApi28() {
    val app = mock(Context::class.java)
    val pm = mock(PackageManager::class.java)
    val info = mock(PackageInfo::class.java)
    info.versionCode = 23
    `when`(app.packageManager).thenReturn(pm)
    `when`(pm.getPackageInfo("one", 0)).thenReturn(info)
    assertEquals(mapOf("one" to 23L), queryInstalledPackageVersions(app, setOf("one"), 24))
    verify(info, never()).longVersionCode
    verify(pm, never()).getInstalledPackages(anyInt())
  }

  @Test fun unexpectedPackageQueryFailureIsUnavailableAndRetryDoesNotCacheAbsence() {
    val app = mock(Context::class.java)
    val pm = mock(PackageManager::class.java)
    val info = mock(PackageInfo::class.java)
    `when`(app.applicationContext).thenReturn(app)
    `when`(app.packageManager).thenReturn(pm)
    `when`(info.longVersionCode).thenReturn(7L)
    `when`(pm.getPackageInfo("one", 0)).thenThrow(SecurityException("generated failure")).thenReturn(info)
    val cache = InstalledVersionInventory({ 100L }, { _, _ -> true }, { context, requested ->
      queryInstalledPackageVersions(context, requested, 29)
    })
    assertNull(cache.snapshot(app, setOf("one")))
    assertEquals(7L, requireNotNull(cache.snapshot(app, setOf("one"))).versionCode("one"))
    verify(pm, times(2)).getPackageInfo("one", 0)
    verify(pm, never()).getInstalledPackages(anyInt())
  }

  @Test fun emptyPackageQueryDoesNotAccessPackageManager() {
    val app = mock(Context::class.java)
    assertEquals(emptyMap<String, Long>(), queryInstalledPackageVersions(app, emptySet(), 29))
    verifyNoInteractions(app)
  }
}
