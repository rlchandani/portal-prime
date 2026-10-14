/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RecentAppLabelCacheTest {
    private val key = RecentAppLabelKey("fixture.first", 1L, "en-US")

    @Test fun oneHundredWarmReadsLoadOneRawLabel() {
        val cache = RecentAppLabelCache()
        var loads = 0
        repeat(100) {
            assertEquals("Raw app label", cache.read(key.copy()) { loads++; "Raw app label" })
        }
        assertEquals(1, loads)
    }

    @Test fun packagesKeepTheirOwnLabelsWithinTheSameOwnerEpoch() {
        val cache = RecentAppLabelCache()
        var loads = 0
        val second = key.copy(packageName = "fixture.second")
        assertEquals("First", cache.read(key) { loads++; "First" })
        assertEquals("Second", cache.read(second) { loads++; "Second" })
        assertEquals("First", cache.read(key) { loads++; "Wrong first" })
        assertEquals("Second", cache.read(second) { loads++; "Wrong second" })
        assertEquals(2, loads)
    }

    @Test fun packageRevisionChangesClearLabelsFromThePreviousOwner() {
        val cache = RecentAppLabelCache()
        val second = key.copy(packageName = "fixture.second")
        cache.read(key) { "Old first" }
        cache.read(second) { "Old second" }
        val updated = key.copy(packageRevision = 2L)
        assertEquals("New first", cache.read(updated) { "New first" })
        assertEquals("New first", cache.read(updated) { throw AssertionError("Warm epoch reloaded") })
        // Re-entering the old owner must load again, even for another cached package.
        assertEquals("Reloaded second", cache.read(second) { "Reloaded second" })
        assertEquals("Reloaded first", cache.read(key) { "Reloaded first" })
    }

    @Test fun configurationChangesReloadLocalizedLabelsAndClearThePreviousOwner() {
        val cache = RecentAppLabelCache()
        assertEquals("English", cache.read(key) { "English" })
        val localized = key.copy(configuration = "fr-FR")
        assertEquals("French", cache.read(localized) { "French" })
        assertEquals("French", cache.read(localized) { throw AssertionError("Localized label reloaded") })
        assertEquals("Fresh English", cache.read(key) { "Fresh English" })
    }

    @Test fun loaderErrorsPropagateAndTheNextReadCanRetry() {
        val cache = RecentAppLabelCache()
        val failure = IllegalStateException("generated-label-load-failure")
        var loads = 0
        val thrown = runCatching { cache.read(key) { loads++; throw failure } }.exceptionOrNull()
        assertSame(failure, thrown)
        assertEquals("Recovered", cache.read(key) { loads++; "Recovered" })
        assertEquals("Recovered", cache.read(key) { loads++; "Unexpected reload" })
        assertEquals(2, loads)
    }

    @Test fun aSuccessfulEmptyLabelIsCached() {
        val cache = RecentAppLabelCache()
        var loads = 0
        repeat(20) { assertEquals("", cache.read(key) { loads++; "" }) }
        assertEquals(1, loads)
    }

    @Test fun capacityEvictsTheLeastRecentlyReadPackage() {
        val cache = RecentAppLabelCache(maxEntries = 2)
        var loads = 0
        fun read(pkg: String): String = cache.read(key.copy(packageName = pkg)) { "label-${++loads}" }
        assertEquals("label-1", read("first"))
        assertEquals("label-2", read("second"))
        assertEquals("label-1", read("first"))
        assertEquals("label-3", read("third"))
        assertEquals("label-1", read("first"))
        assertEquals("label-4", read("second"))
        assertEquals(4, loads)
    }

    @Test fun defaultCapacityRetainsSixtyFourLabelsAndEvictsTheOldest() {
        val cache = RecentAppLabelCache()
        var loads = 0
        fun read(index: Int): String = cache.read(key.copy(packageName = "fixture.$index")) {
            loads++; "label-$index"
        }
        repeat(64) { assertEquals("label-$it", read(it)) }
        assertEquals("label-63", read(63))
        assertEquals(64, loads)
        assertEquals("label-64", read(64))
        for (index in 1..64) assertEquals("label-$index", read(index))
        assertEquals(65, loads)
        assertEquals("label-0", read(0))
        assertEquals(66, loads)
    }

    @Test fun blockedOldLoadCannotDelayOrRepopulateAReactivatedOwnerEpoch() {
        val cache = RecentAppLabelCache()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val old = workers.submit(Callable<String> {
                cache.read(key) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    "Obsolete"
                }
            })
            assertTrue("Old loader must enter", entered.await(2, TimeUnit.SECONDS))
            val updated = key.copy(packageRevision = 2L)
            assertEquals("Current", workers.submit(Callable<String> {
                cache.read(updated) { "Current" }
            }).get(2, TimeUnit.SECONDS))
            assertEquals("Current", cache.read(updated) { throw AssertionError("Current owner reloaded") })
            // Same owner values as the blocked read, but a newer cache generation (ABA).
            assertEquals("Fresh", workers.submit(Callable<String> {
                cache.read(key) { "Fresh" }
            }).get(2, TimeUnit.SECONDS))
            release.countDown()
            assertEquals("Obsolete", old.get(2, TimeUnit.SECONDS))
            assertEquals("Fresh", cache.read(key) { throw AssertionError("Old load poisoned the active epoch") })
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue("Label workers must stop", workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
