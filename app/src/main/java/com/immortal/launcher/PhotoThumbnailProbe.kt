/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

/** Fixed generated-thumbnail concurrency check; no user photo paths or bytes are exported. */
internal object PhotoThumbnailProbe {
    private val running = AtomicBoolean(false)
    private var pendingPool: ExecutorService? = null
    private var pendingFixture: File? = null

    /** A timed-out group keeps its lease; a later request can reap it only after termination. */
    private fun reapCleanup() = synchronized(running) {
        val pool = pendingPool ?: return@synchronized
        if (!pool.isTerminated) return@synchronized
        if (runCatching { cleanupFixture(pendingFixture) }.getOrDefault(false)) {
            pendingPool = null
            pendingFixture = null
            running.set(false)
        }
    }
    private fun cleanupFixture(root: File?): Boolean {
        if (root == null) return true
        return RemoteCompanionPhotos.discardThumbnail(File(root, "Pictures/thumbnail.jpg")) && root.deleteRecursively()
    }
    private fun response(status: Int, value: JSONObject) = FleetHttpServer.Response(status, value.toString())
    private fun error(status: Int, code: String) = response(status, JSONObject().put("ok", false).put("error", code))

    fun handle(context: Context, request: FleetHttpServer.Request): FleetHttpServer.Response {
        if (!BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) return error(403, "photo_thumbnail_probe_disabled")
        if (!DevMode.isEnabled(context)) return error(403, "dev_mode_required")
        if (request.method != "GET") return error(405, "method_not_allowed")
        if (request.query.isNotEmpty()) return error(400, "query_not_supported")
        if (Looper.myLooper() == Looper.getMainLooper()) return error(503, "worker_required")
        if (HomeUiDiagnostics.collectorBusy()) return error(409, "photo_thumbnail_perf_busy")
        reapCleanup()
        if (!running.compareAndSet(false, true)) return error(409, "photo_thumbnail_probe_busy")
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var fixture: File? = null
        var result: JSONObject? = null
        var deleted = false
        var terminated = false
        var cacheRemoved = false
        try {
            val bitmap = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
            val image = try {
                bitmap.eraseColor(0xff4b79a1.toInt())
                ByteArrayOutputStream().use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)); out.toByteArray()
                }
            } finally { bitmap.recycle() }
            val root = File.createTempFile("immortal-generated-thumbnail-", ".dir", context.cacheDir)
            fixture = root
            check(root.delete() && root.mkdir())
            val folder = File(root, "Pictures").apply { check(mkdir()) }
            val file = File(folder, "thumbnail.jpg").apply { writeBytes(image) }
            val decodeCount = java.util.concurrent.atomic.AtomicInteger()
            fun photoRequest(thumbnail: Boolean) = FleetHttpServer.Request("GET", "/remote/photo",
                "path=%2Fsdcard%2FPictures%2Fthumbnail.jpg" + if (thumbnail) "&thumbnail=1" else "",
                emptyMap(), ByteArrayInputStream(byteArrayOf()), 0)
            fun bytes(value: FleetHttpServer.Response) = value.use {
                check(it.status == 200)
                ByteArrayOutputStream().use { out -> it.streamWriter!!(out); out.toByteArray() }
            }
            val thumbnail = pool.submit<ByteArray> {
                bytes(RemoteCompanionPhotos.handle(photoRequest(true), root) {
                    decodeCount.incrementAndGet(); entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                })
            }
            check(entered.await(2, TimeUnit.SECONDS))
            val readStart = SystemClock.elapsedRealtimeNanos()
            val read = pool.submit<ByteArray> { bytes(RemoteCompanionPhotos.handle(photoRequest(false), root)) }
            val finished = try { read.get(750, TimeUnit.MILLISECONDS); release.count == 1L && !thumbnail.isDone }
                catch (_: TimeoutException) { false }
            val readMs = (SystemClock.elapsedRealtimeNanos() - readStart) / 1_000_000
            release.countDown()
            val thumb = thumbnail.get(5, TimeUnit.SECONDS)
            val readImage = read.get(2, TimeUnit.SECONDS)
            val cached = bytes(RemoteCompanionPhotos.handle(photoRequest(true), root) { decodeCount.incrementAndGet() })
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(thumb, 0, thumb.size, bounds)
            File(folder, "invalid.jpg").writeText("invalid image fixture")
            val invalid = RemoteCompanionPhotos.handle(FleetHttpServer.Request("GET", "/remote/photo",
                "path=%2Fsdcard%2FPictures%2Finvalid.jpg&thumbnail=1", emptyMap(), ByteArrayInputStream(byteArrayOf()), 0), root)
                .use { it.status == 415 && JSONObject(it.jsonBytes!!.toString(Charsets.UTF_8)).getString("error") == "invalid_image" }
            result = JSONObject().put("ok", true).put("readFinishedWhileDecodePaused", finished)
                .put("readBytesPreserved", image.contentEquals(readImage) && image.contentEquals(file.readBytes()))
                .put("thumbnailDecoded", bounds.outWidth == 512 && bounds.outHeight == 384)
                .put("invalidImageRejected", invalid)
                .put("cacheReused", decodeCount.get() == 1 && thumb.contentEquals(cached))
                .put("originalBytes", image.size).put("readBytes", readImage.size).put("thumbnailBytes", thumb.size)
                .put("thumbWidth", bounds.outWidth).put("thumbHeight", bounds.outHeight).put("readMs", readMs)
        } catch (_: Exception) { result = null }
        finally {
            release.countDown()
            pool.shutdownNow()
            terminated = runCatching { pool.awaitTermination(5, TimeUnit.SECONDS) }.getOrDefault(false)
            // Never remove an active worker's fixture or admit another worker group.
            if (terminated) {
                cacheRemoved = runCatching { fixture?.let { RemoteCompanionPhotos.discardThumbnail(File(it, "Pictures/thumbnail.jpg")) } != false }.getOrDefault(false)
                if (cacheRemoved) deleted = runCatching { fixture?.deleteRecursively() != false }.getOrDefault(false)
            }
            if (terminated && deleted) running.set(false)
            else synchronized(running) { pendingFixture = fixture; pendingPool = pool }
        }
        return result?.let { response(200, it.put("fixtureDeleted", deleted).put("fixtureCacheRemoved", cacheRemoved).put("workersTerminated", terminated)) }
            ?: error(503, "photo_thumbnail_probe_failed")
    }
}
