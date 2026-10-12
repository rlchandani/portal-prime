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
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RemotePhotoThumbnailCacheTest {
    @Test fun slowDecodeDoesNotBlockCachedThumbnail() = stalled(false)
    @Test fun slowDecodeDoesNotBlockAnotherDecoder() = stalled(true)
    private fun stalled(otherMiss: Boolean) {
        val cache = RemotePhotoThumbnailCache()
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val ready = byteArrayOf(7)
        cache.load(cache.snapshot("ready")) { ready }
        val ticket = cache.snapshot("slow")
        try {
            val slow = pool.submit<ByteArray?> { cache.load(ticket) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); byteArrayOf(1)
            } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val other = pool.submit<ByteArray?> { cache.load(cache.snapshot(if (otherMiss) "other" else "ready")) {
                if (!otherMiss) error("cached_value_was_decoded"); byteArrayOf(8)
            } }
            assertArrayEquals(if (otherMiss) byteArrayOf(8) else ready, other.get(500, TimeUnit.MILLISECONDS))
            assertEquals(1L, release.count)
            release.countDown(); assertArrayEquals(byteArrayOf(1), slow.get(2, TimeUnit.SECONDS))
        } finally { release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun clearDoesNotWaitForDecodeOrReinsertItsOldBytes() {
        val cache = RemotePhotoThumbnailCache()
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val ticket = cache.snapshot("photo")
        try {
            val slow = pool.submit<ByteArray?> { cache.load(ticket) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); byteArrayOf(1)
            } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            pool.submit { cache.clear() }.get(500, TimeUnit.MILLISECONDS)
            release.countDown(); assertArrayEquals(byteArrayOf(1), slow.get(2, TimeUnit.SECONDS))
            assertNull(cache.snapshot("photo").cached)
            assertArrayEquals(byteArrayOf(2), cache.load(cache.snapshot("photo")) { byteArrayOf(2) })
        } finally { release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun atMostTwoPixelDecodersRunAtOnce() {
        val cache = RemotePhotoThumbnailCache()
        val pool = Executors.newFixedThreadPool(3)
        val firstTwo = CountDownLatch(2); val release = CountDownLatch(1)
        val active = AtomicInteger(); val peak = AtomicInteger()
        val tickets = (1..3).map { cache.snapshot("photo$it") }
        try {
            val futures = tickets.map { ticket -> pool.submit<ByteArray?> { cache.load(ticket) {
                val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
                firstTwo.countDown(); check(release.await(5, TimeUnit.SECONDS))
                active.decrementAndGet(); byteArrayOf(1)
            } } }
            assertTrue(firstTwo.await(2, TimeUnit.SECONDS))
            assertEquals(2, active.get())
            release.countDown(); futures.forEach { assertNotNull(it.get(2, TimeUnit.SECONDS)) }
            assertEquals(2, peak.get())
        } finally { release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun byteAndEntryLimitsEvictTheLeastRecentlyUsedValue() {
        val cache = RemotePhotoThumbnailCache(maxBytes = 6, maxEntries = 2, maxItemBytes = 4)
        fun put(key: String) = cache.load(cache.snapshot(key)) { ByteArray(3) }
        put("a"); put("b"); assertNotNull(cache.snapshot("a").cached); put("c")
        assertNull(cache.snapshot("b").cached); assertNotNull(cache.snapshot("a").cached)
        assertNotNull(cache.snapshot("c").cached)
        val tiny = RemotePhotoThumbnailCache(maxBytes = 4, maxEntries = 32, maxItemBytes = 4)
        tiny.load(tiny.snapshot("a")) { ByteArray(3) }; tiny.load(tiny.snapshot("b")) { ByteArray(3) }
        assertNull(tiny.snapshot("a").cached); assertNotNull(tiny.snapshot("b").cached)
    }
    @Test fun failedAndOversizedDecodesAreNotCachedAndReleaseTheirSlot() {
        val cache = RemotePhotoThumbnailCache(maxItemBytes = 2)
        assertNull(cache.load(cache.snapshot("empty")) { null })
        assertNull(cache.load(cache.snapshot("large")) { ByteArray(3) })
        assertTrue(runCatching { cache.load(cache.snapshot("throw")) { error("decoder_failed") } }.isFailure)
        for (key in listOf("empty", "large", "throw")) assertNull(cache.snapshot(key).cached)
        assertArrayEquals(byteArrayOf(1), cache.load(cache.snapshot("next")) { byteArrayOf(1) })
    }
    @Test fun staleTicketCannotReplaceNewGenerationBytes() {
        val cache = RemotePhotoThumbnailCache()
        val old = cache.snapshot("same")
        cache.clear(); cache.load(cache.snapshot("same")) { byteArrayOf(2) }
        assertArrayEquals(byteArrayOf(1), cache.load(old) { byteArrayOf(1) })
        assertArrayEquals(byteArrayOf(2), cache.snapshot("same").cached)
    }
    @Test fun discardingFixturePreservesUnrelatedCachedPhoto() {
        val cache = RemotePhotoThumbnailCache()
        cache.load(cache.snapshot("user")) { byteArrayOf(1) }
        cache.load(cache.snapshot("fixture")) { byteArrayOf(2) }
        assertTrue(cache.discard("fixture")); assertNull(cache.snapshot("fixture").cached)
        assertArrayEquals(byteArrayOf(1), cache.snapshot("user").cached)
    }
}
