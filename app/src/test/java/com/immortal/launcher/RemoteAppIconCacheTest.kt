/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RemoteAppIconCacheTest {
    private val key = RemoteAppIconKey("owner", "app", 144, "light")
    private fun cache() = RemoteAppIconCache<String>({ _, _ -> true })

    @Test fun twentyWarmRequestsEncodeOnce() {
        val cache = cache()
        var calls = 0
        repeat(20) { assertArrayEquals(byteArrayOf(1), cache.png("ctx", key) {
            calls++; byteArrayOf(1)
        }) }
        assertEquals(1, calls)
    }

    @Test fun eightConcurrentRequestsShareOneEncoder() {
        val cache = cache()
        val calls = AtomicInteger()
        val started = CountDownLatch(8)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val work = List(8) { pool.submit<ByteArray?> {
                started.countDown()
                cache.png("ctx", key) {
                    calls.incrementAndGet(); entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS)); byteArrayOf(7)
                }
            } }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            release.countDown()
            work.forEach { assertArrayEquals(byteArrayOf(7), it.get(3, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(2, TimeUnit.SECONDS) }
    }

    @Test fun differentIconsCanEncodeConcurrently() {
        val cache = cache()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val work = List(2) { n -> pool.submit<ByteArray?> {
                cache.png("ctx", key.copy(packageName = "app$n")) {
                    entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); byteArrayOf(n.toByte())
                }
            } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            release.countDown()
            work.forEachIndexed { n, task -> assertArrayEquals(byteArrayOf(n.toByte()), task.get(3, TimeUnit.SECONDS)) }
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(2, TimeUnit.SECONDS) }
    }

    @Test fun packageSizeOwnerAndResourcesAreSeparateKeys() {
        val cache = cache(); var calls = 0
        val keys = listOf(key, key.copy(packageName = "other"), key.copy(sizePx = 512),
            key.copy(ownerPackage = "another-owner"), key.copy(resources = "dark"))
        repeat(2) { keys.forEachIndexed { n, k ->
            assertArrayEquals(byteArrayOf(n.toByte()), cache.png("ctx", k) {
                calls++; byteArrayOf(n.toByte())
            })
        } }
        assertEquals(5, calls)
    }

    @Test fun expiryStartsWhenEncodingCompletes() {
        var now = 0L; var calls = 0
        val cache = RemoteAppIconCache<String>({ _, _ -> true }, ttlMillis = 10, nowMillis = { now })
        cache.png("ctx", key) { calls++; now = 50; byteArrayOf(1) }
        now = 59
        assertArrayEquals(byteArrayOf(1), cache.png("ctx", key) { calls++; byteArrayOf(2) })
        now = 60
        assertArrayEquals(byteArrayOf(2), cache.png("ctx", key) { calls++; byteArrayOf(2) })
        assertEquals(2, calls)
    }

    @Test fun packageEventRefreshesTheNextRequest() {
        var changed: (() -> Unit)? = null; var calls = 0
        val cache = RemoteAppIconCache<String>({ _, invalidate -> changed = invalidate; true })
        cache.png("ctx", key) { calls++; byteArrayOf(1) }
        requireNotNull(changed).invoke()
        assertArrayEquals(byteArrayOf(2), cache.png("ctx", key) { calls++; byteArrayOf(2) })
        assertArrayEquals(byteArrayOf(2), cache.png("ctx", key) { calls++; byteArrayOf(3) })
        assertEquals(2, calls)
    }

    @Test fun invalidatedInflightResultCannotOverwriteReplacement() {
        val cache = cache(); val calls = AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val old = pool.submit<ByteArray?> { cache.png("ctx", key) {
                calls.incrementAndGet(); entered.countDown()
                check(release.await(3, TimeUnit.SECONDS)); byteArrayOf(1)
            } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            cache.invalidate()
            assertArrayEquals(byteArrayOf(2), cache.png("ctx", key) { calls.incrementAndGet(); byteArrayOf(2) })
            release.countDown()
            assertArrayEquals(byteArrayOf(1), old.get(3, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(2), cache.png("ctx", key) { calls.incrementAndGet(); byteArrayOf(3) })
            assertEquals(2, calls.get())
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(2, TimeUnit.SECONDS) }
    }

    @Test fun nullResultsAreRetried() {
        val cache = cache(); var calls = 0
        repeat(2) { assertNull(cache.png("ctx", key) { calls++; null }) }
        assertArrayEquals(byteArrayOf(1), cache.png("ctx", key) { calls++; byteArrayOf(1) })
        assertArrayEquals(byteArrayOf(1), cache.png("ctx", key) { calls++; byteArrayOf(2) })
        assertEquals(3, calls)
    }

    @Test fun failedEncodesAreRetried() {
        val cache = cache(); var calls = 0
        assertTrue(runCatching { cache.png("ctx", key) { calls++; throw IOException("fixture") } }.exceptionOrNull() is IOException)
        assertArrayEquals(byteArrayOf(1), cache.png("ctx", key) { calls++; byteArrayOf(1) })
        assertArrayEquals(byteArrayOf(1), cache.png("ctx", key) { calls++; byteArrayOf(2) })
        assertEquals(2, calls)
    }

    @Test fun emptyAndOversizedValuesAreNotRetained() {
        val cache = RemoteAppIconCache<String>({ _, _ -> true }, maxRetainedBytes = 4)
        var calls = 0
        repeat(2) { assertArrayEquals(byteArrayOf(), cache.png("ctx", key) { calls++; byteArrayOf() }) }
        repeat(2) { assertArrayEquals(ByteArray(5), cache.png("ctx", key.copy(packageName = "large")) { calls++; ByteArray(5) }) }
        assertEquals(4, calls)
    }

    @Test fun capacityEvictsTheLeastRecentlyUsedIcon() {
        val cache = RemoteAppIconCache<String>({ _, _ -> true }, maxEntries = 2)
        var calls = 0
        fun get(pkg: String) = cache.png("ctx", key.copy(packageName = pkg)) { byteArrayOf((++calls).toByte()) }
        assertArrayEquals(byteArrayOf(1), get("a"))
        assertArrayEquals(byteArrayOf(2), get("b"))
        assertArrayEquals(byteArrayOf(1), get("a"))
        assertArrayEquals(byteArrayOf(3), get("c"))
        assertArrayEquals(byteArrayOf(4), get("b"))
        assertEquals(4, calls)
    }

    @Test fun inputAndOutputMutationCannotPoisonTheCache() {
        val cache = cache(); val input = byteArrayOf(1); var calls = 0
        val first = requireNotNull(cache.png("ctx", key) { calls++; input })
        input[0] = 9; first[0] = 8
        val next = requireNotNull(cache.png("ctx", key) { calls++; input })
        assertArrayEquals(byteArrayOf(1), next)
        assertNotSame(first, next)
        assertEquals(1, calls)
    }

    @Test fun failedSubscriptionKeepsTheUncachedFallback() {
        var subscriptions = 0; var calls = 0
        val cache = RemoteAppIconCache<String>({ _, _ -> subscriptions++; false })
        assertArrayEquals(byteArrayOf(1), cache.png("ctx", key) { calls++; byteArrayOf(1) })
        assertArrayEquals(byteArrayOf(2), cache.png("ctx", key) { calls++; byteArrayOf(2) })
        assertEquals(2, calls); assertEquals(2, subscriptions)
    }

    @Test fun resourceChangesDuringRenderDoNotRetainWrongPixels() {
        val cache = cache(); var calls = 0
        assertArrayEquals(byteArrayOf(1), cache.png("ctx", key, canRetain = { false }) { calls++; byteArrayOf(1) })
        assertArrayEquals(byteArrayOf(2), cache.png("ctx", key) { calls++; byteArrayOf(2) })
        assertArrayEquals(byteArrayOf(2), cache.png("ctx", key) { calls++; byteArrayOf(3) })
        assertEquals(2, calls)
    }
}
