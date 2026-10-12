/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ApprovedSourcesPreviewSlotsTest {
    @Test fun cachedPreviewReturnsWhileBothDecodeSlotsAreOccupied() {
        val slots = Semaphore(2); slots.acquire(2)
        val pool = Executors.newSingleThreadExecutor(); val value = Any()
        try {
            val hit = pool.submit<Any?> { approvedSourcesPreviewWithSlots(slots, { value }, { error("cached_preview_decoded") }) }
            assertSame(value, hit.get(500, TimeUnit.MILLISECONDS))
            assertEquals(0, slots.availablePermits())
        } finally { slots.release(2); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun queuedMissRechecksCurrentMetadataAndCacheAfterWaiting() {
        val slots = Semaphore(0); val pool = Executors.newSingleThreadExecutor()
        val lookedUp = CountDownLatch(1); val current = AtomicReference<Any?>(); val replacement = Any()
        try {
            val load = pool.submit<Any?> { approvedSourcesPreviewWithSlots(slots,
                { lookedUp.countDown(); current.get() }, { error("queued_preview_was_already_loaded") }) }
            assertTrue(lookedUp.await(2, TimeUnit.SECONDS))
            current.set(replacement); slots.release()
            assertSame(replacement, load.get(2, TimeUnit.SECONDS)); assertEquals(1, slots.availablePermits())
        } finally { slots.release(); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun noMoreThanTwoColdDecodersRunTogether() {
        val slots = Semaphore(2); val pool = Executors.newFixedThreadPool(3)
        val entered = CountDownLatch(2); val release = CountDownLatch(1)
        val active = AtomicInteger(); val peak = AtomicInteger()
        try {
            val loads = (1..3).map { pool.submit<Any?> { approvedSourcesPreviewWithSlots(slots, { null }, {
                val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); active.decrementAndGet(); Any()
            }) } }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); assertEquals(2, active.get())
            release.countDown(); loads.forEach { assertNotNull(it.get(2, TimeUnit.SECONDS)) }
            assertEquals(2, peak.get()); assertEquals(2, slots.availablePermits())
        } finally { release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun canceledCacheHitIsNotDeliveredOrDecoded() {
        val slots = Semaphore(2); val pool = Executors.newSingleThreadExecutor(); val lookups = AtomicInteger()
        try {
            val canceled = pool.submit<Boolean> {
                Thread.currentThread().interrupt()
                try { approvedSourcesPreviewWithSlots(slots, { lookups.incrementAndGet(); Any() }, { error("decoded") }); false }
                catch (_: InterruptedException) { true }
                finally { Thread.interrupted() }
            }
            assertTrue(canceled.get(2, TimeUnit.SECONDS)); assertEquals(0, lookups.get())
            assertEquals(2, slots.availablePermits())
        } finally { pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun failedDecodeReleasesCapacityForTheNextLoad() {
        val slots = Semaphore(2)
        assertTrue(runCatching { approvedSourcesPreviewWithSlots<Any>(slots, { null }, { error("decoder_failed") }) }.isFailure)
        assertEquals(2, slots.availablePermits())
        val value = Any(); assertSame(value, approvedSourcesPreviewWithSlots(slots, { null }, { value }))
        assertEquals(2, slots.availablePermits())
    }
    @Test fun missingImageDoesNotConsumeCapacityAfterReturning() {
        val slots = Semaphore(2)
        assertNull(approvedSourcesPreviewWithSlots<Any>(slots, { null }, { null }))
        assertEquals(2, slots.availablePermits())
    }
}
