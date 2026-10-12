/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Fixed generated-preview cache check; no user photo paths or bytes are exported. */
internal object SourceCacheProbe {
    private val running = AtomicBoolean(false)
    private var pendingPool: ExecutorService? = null
    private var pendingFixture: File? = null
    private var pendingOwned: MutableList<Bitmap>? = null

    /** A timed-out group keeps its lease; cleanup runs only after all its workers terminate. */
    private fun reapCleanup() = synchronized(running) {
        val pool = pendingPool ?: return@synchronized
        if (!pool.isTerminated) return@synchronized
        val cleaned = runCatching { cleanupFixture(pendingFixture, pendingOwned ?: emptyList()) }.getOrNull()
        if (cleaned == (true to true)) {
            pendingPool = null; pendingFixture = null; pendingOwned = null
            running.set(false)
        }
    }
    private fun cleanupFixture(root: File?, owned: List<Bitmap>): Pair<Boolean, Boolean> {
        var removed = true
        if (root != null) for (name in listOf("warm.jpg", "cold1.jpg", "cold2.jpg")) {
            val item = MediaItem(File(root, name).path, false)
            val bitmap = approvedSourcesDiscardPreview(item)
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            removed = (approvedSourcesDiscardPreview(item) == null) && removed
        }
        for (bitmap in owned) if (!bitmap.isRecycled) bitmap.recycle()
        return (root?.deleteRecursively() != false) to removed
    }
    private fun response(status: Int, value: JSONObject) = FleetHttpServer.Response(status, value.toString())
    private fun error(status: Int, code: String) = response(status, JSONObject().put("ok", false).put("error", code))

    fun handle(context: Context, request: FleetHttpServer.Request): FleetHttpServer.Response {
        if (!BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) return error(403, "source_cache_probe_disabled")
        if (!DevMode.isEnabled(context)) return error(403, "dev_mode_required")
        if (request.method != "GET") return error(405, "method_not_allowed")
        if (request.query.isNotEmpty()) return error(400, "query_not_supported")
        if (Looper.myLooper() == Looper.getMainLooper()) return error(503, "worker_required")
        if (HomeUiDiagnostics.collectorBusy()) return error(409, "source_cache_perf_busy")
        reapCleanup()
        if (!running.compareAndSet(false, true)) return error(409, "source_cache_probe_busy")
        val pool = Executors.newFixedThreadPool(3)
        val owned = java.util.Collections.synchronizedList(mutableListOf<Bitmap>())
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        var fixture: File? = null
        var result: JSONObject? = null
        var deleted = false
        var terminated = false
        var cacheRemoved = false
        try {
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val image = try {
                bitmap.eraseColor(0xff4b79a1.toInt())
                ByteArrayOutputStream().use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)); out.toByteArray()
                }
            } finally { bitmap.recycle() }
            val root = File.createTempFile("immortal-generated-source-cache-", ".dir", context.cacheDir)
            fixture = root
            check(root.delete() && root.mkdir())
            val items = listOf("warm.jpg", "cold1.jpg", "cold2.jpg").map {
                MediaItem(File(root, it).apply { writeBytes(image) }.path, false)
            }
            val warm = approvedSourcesThumbnail(items[0])?.also { owned.add(it) } ?: error("warm_preview_unavailable")
            val decodeCount = java.util.concurrent.atomic.AtomicInteger()
            val cold = items.drop(1).map { item -> pool.submit<Bitmap?> {
                approvedSourcesThumbnail(item) {
                    decodeCount.incrementAndGet(); entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }?.also { owned.add(it) }
            } }
            check(entered.await(2, TimeUnit.SECONDS))
            val readStart = SystemClock.elapsedRealtimeNanos()
            val read = pool.submit<Bitmap?> {
                approvedSourcesThumbnail(items[0]) { error("cached_preview_decoded") }?.also { owned.add(it) }
            }
            val finished = try {
                read.get(750, TimeUnit.MILLISECONDS)
                release.count == 1L && cold.none { it.isDone }
            } catch (_: TimeoutException) { false }
            val readMs = (SystemClock.elapsedRealtimeNanos() - readStart) / 1_000_000
            release.countDown()
            cold.forEach { check(it.get(5, TimeUnit.SECONDS) != null) }
            val cached = read.get(2, TimeUnit.SECONDS)
            result = JSONObject().put("ok", true).put("cachedReturnedWhileDecodersPaused", finished)
                .put("cachedIdentityReused", cached === warm && !warm.isRecycled)
                .put("cachedWidth", warm.width).put("cachedHeight", warm.height)
                .put("cachedBytes", warm.allocationByteCount).put("coldDecodes", decodeCount.get())
                .put("cachedReadMs", readMs)
        } catch (_: Exception) { result = null }
        finally {
            release.countDown()
            pool.shutdownNow()
            terminated = runCatching { pool.awaitTermination(5, TimeUnit.SECONDS) }.getOrDefault(false)
            // Retain both fixture and bitmap ownership if any worker is still active.
            if (terminated) runCatching { cleanupFixture(fixture, owned) }.getOrNull()?.let {
                deleted = it.first; cacheRemoved = it.second
            }
            if (terminated && deleted && cacheRemoved) running.set(false)
            else synchronized(running) { pendingFixture = fixture; pendingPool = pool; pendingOwned = owned }
        }
        return result?.let { response(200, it.put("fixtureDeleted", deleted).put("fixtureCacheRemoved", cacheRemoved).put("workersTerminated", terminated)) }
            ?: error(503, "source_cache_probe_failed")
    }
}
