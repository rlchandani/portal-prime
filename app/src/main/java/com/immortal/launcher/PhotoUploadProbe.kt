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

/** Fixed generated JPEG check against the actual routes; never accesses the user's photos. */
internal object PhotoUploadProbe {
    private val running = AtomicBoolean(false)
    private var pendingPool: ExecutorService? = null
    private var pendingFixture: File? = null

    /** A timed-out group keeps its lease; a later request can reap it only after termination. */
    private fun reapCleanup() = synchronized(running) {
        val pool = pendingPool ?: return@synchronized
        if (!pool.isTerminated) return@synchronized
        if (runCatching { pendingFixture?.deleteRecursively() != false }.getOrDefault(false)) {
            pendingPool = null
            pendingFixture = null
            running.set(false)
        }
    }
    private fun response(status: Int, value: JSONObject) = FleetHttpServer.Response(status, value.toString())
    private fun error(status: Int, code: String) = response(status, JSONObject().put("ok", false).put("error", code))

    fun handle(context: Context, request: FleetHttpServer.Request): FleetHttpServer.Response {
        if (!BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) return error(403, "photo_upload_probe_disabled")
        if (!DevMode.isEnabled(context)) return error(403, "dev_mode_required")
        if (request.method != "GET") return error(405, "method_not_allowed")
        if (request.query.isNotEmpty()) return error(400, "query_not_supported")
        if (Looper.myLooper() == Looper.getMainLooper()) return error(503, "worker_required")
        if (HomeUiDiagnostics.collectorBusy()) return error(409, "photo_upload_perf_busy")
        reapCleanup()
        if (!running.compareAndSet(false, true)) return error(409, "photo_upload_probe_busy")
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var fixture: File? = null
        var result: JSONObject? = null
        var deleted = false
        var terminated = false
        try {
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val image = try {
                bitmap.eraseColor(0xff4b79a1.toInt())
                ByteArrayOutputStream().use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)); out.toByteArray()
                }
            } finally { bitmap.recycle() }
            val root = File.createTempFile("immortal-generated-upload-", ".dir", context.cacheDir)
            fixture = root
            check(root.delete() && root.mkdir())
            val folder = File(root, "Pictures").apply { check(mkdir()) }
            File(folder, "existing.jpg").writeBytes(image)
            val paused = object : ByteArrayInputStream(image) {
                private var first = true
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (first) {
                        first = false
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    return super.read(buffer, offset, length)
                }
            }
            val upload = pool.submit<FleetHttpServer.Response> {
                RemoteCompanionPhotos.handle(FleetHttpServer.Request("POST", "/remote/photo-upload",
                    "path=%2Fsdcard%2FPictures&name=incoming.jpg", mapOf("content-type" to "image/jpeg"),
                    paused, image.size), root)
            }
            check(entered.await(2, TimeUnit.SECONDS))
            val pausedStart = SystemClock.elapsedRealtimeNanos()
            val noPartial = !File(folder, "incoming.jpg").exists()
            val readStart = SystemClock.elapsedRealtimeNanos()
            val read = pool.submit<ByteArray> {
                RemoteCompanionPhotos.handle(FleetHttpServer.Request("GET", "/remote/photo",
                    "path=%2Fsdcard%2FPictures%2Fexisting.jpg", emptyMap(), ByteArrayInputStream(byteArrayOf()), 0), root)
                    .use { value ->
                        check(value.status == 200)
                        ByteArrayOutputStream().use { out -> value.streamWriter!!(out); out.toByteArray() }
                    }
            }
            val finished = try { read.get(750, TimeUnit.MILLISECONDS); release.count == 1L && !upload.isDone }
                catch (_: TimeoutException) { false }
            val readMs = (SystemClock.elapsedRealtimeNanos() - readStart) / 1_000_000
            val pausedMs = (SystemClock.elapsedRealtimeNanos() - pausedStart) / 1_000_000
            release.countDown()
            val committed = upload.get(5, TimeUnit.SECONDS).use { it.status == 200 }
            val readImage = read.get(2, TimeUnit.SECONDS)
            val preserved = image.contentEquals(readImage) &&
                image.contentEquals(File(folder, "incoming.jpg").readBytes()) &&
                image.contentEquals(File(folder, "existing.jpg").readBytes())
            result = JSONObject().put("ok", true).put("readFinishedWhileUploadPaused", finished)
                .put("noPartialPublication", noPartial).put("uploadCommitted", committed)
                .put("bytesPreserved", preserved).put("uploadBytes", image.size).put("readBytes", readImage.size)
                .put("readMs", readMs).put("pausedMs", pausedMs)
        } catch (_: Exception) { result = null }
        finally {
            release.countDown()
            pool.shutdownNow()
            terminated = runCatching { pool.awaitTermination(5, TimeUnit.SECONDS) }.getOrDefault(false)
            // Never remove an active worker's fixture or admit another worker group.
            if (terminated) deleted = runCatching { fixture?.deleteRecursively() != false }.getOrDefault(false)
            if (terminated && deleted) running.set(false)
            else synchronized(running) { pendingFixture = fixture; pendingPool = pool }
        }
        return result?.let { response(200, it.put("fixtureDeleted", deleted).put("workersTerminated", terminated)) }
            ?: error(503, "photo_upload_probe_failed")
    }
}
