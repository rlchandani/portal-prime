/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import org.json.JSONObject

/** Generated crop proof only: no user image, path or pixel value leaves the app. */
internal object SourcePhotoPreviewProbe {
    private val running = AtomicBoolean(false)
    private fun response(status: Int, value: JSONObject) = FleetHttpServer.Response(status, value.toString())
    private fun error(status: Int, code: String) = response(status, JSONObject().put("ok", false).put("error", code))

    fun handle(context: Context, request: FleetHttpServer.Request): FleetHttpServer.Response {
        if (!BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) return error(403, "source_photo_probe_disabled")
        if (!DevMode.isEnabled(context)) return error(403, "dev_mode_required")
        if (request.method != "GET") return error(405, "method_not_allowed")
        if (request.query.isNotEmpty()) return error(400, "query_not_supported")
        if (Looper.myLooper() == Looper.getMainLooper()) return error(503, "worker_required")
        if (HomeUiDiagnostics.collectorBusy()) return error(409, "source_photo_perf_busy")
        if (!running.compareAndSet(false, true)) return error(409, "source_photo_probe_busy")
        var file: File? = null
        var legacy: Bitmap? = null
        var preview: Bitmap? = null
        val target = ApprovedSourcePreviewSize(528, 352)
        var result: JSONObject? = null
        var deleted = false
        var removed = false
        try {
            file = File.createTempFile("immortal-generated-photo-preview-", ".jpg", context.cacheDir)
            val original = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
            try {
                original.eraseColor(0xff4b79a1.toInt())
                val canvas = Canvas(original)
                val paint = Paint().apply { color = 0xffcc3344.toInt() }
                canvas.drawRect(0f, 0f, 4000f, 100f, paint)
                paint.color = 0xff34ad62.toInt()
                canvas.drawRect(0f, 2900f, 4000f, 3000f, paint)
                file.outputStream().use { check(original.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally { original.recycle() }
            val item = MediaItem(file.path, false)
            val legacyStart = SystemClock.elapsedRealtimeNanos()
            legacy = approvedSourcesThumbnail(item) ?: error("generated_legacy_preview_unavailable")
            val legacyMs = (SystemClock.elapsedRealtimeNanos() - legacyStart) / 1_000_000
            val previewStart = SystemClock.elapsedRealtimeNanos()
            preview = approvedSourcesThumbnail(item, target) ?: error("generated_photo_preview_unavailable")
            val previewMs = (SystemClock.elapsedRealtimeNanos() - previewStart) / 1_000_000
            val cached = approvedSourcesThumbnail(item, target)
            fun centerColor(x: Int, y: Int): Boolean {
                val color = preview.getPixel(x, y)
                return abs(Color.red(color) - 75) <= 3 && abs(Color.green(color) - 121) <= 3 &&
                    abs(Color.blue(color) - 161) <= 3
            }
            val centered = centerColor(0, 0) && centerColor(preview.width - 1, 0) &&
                centerColor(0, preview.height - 1) && centerColor(preview.width - 1, preview.height - 1) &&
                centerColor(preview.width / 2, preview.height / 2)
            result = JSONObject().put("ok", true).put("cachedIdentityReused", cached === preview)
                .put("centerCropVerified", centered).put("inputWidth", 4000).put("inputHeight", 3000)
                .put("targetWidth", target.width).put("targetHeight", target.height)
                .put("legacyWidth", legacy.width).put("legacyHeight", legacy.height)
                .put("legacyBytes", legacy.allocationByteCount).put("legacyDecodeMs", legacyMs)
                .put("previewWidth", preview.width).put("previewHeight", preview.height)
                .put("previewBytes", preview.allocationByteCount).put("previewDecodeMs", previewMs)
        } catch (_: Exception) { result = null }
        finally {
            file?.let {
                val item = MediaItem(it.path, false)
                val owned = listOfNotNull(approvedSourcesDiscardPreview(item), approvedSourcesDiscardPreview(item, target), legacy, preview)
                owned.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
                removed = approvedSourcesDiscardPreview(item) == null && approvedSourcesDiscardPreview(item, target) == null
                deleted = it.delete()
            }
            running.set(false)
        }
        return result?.let { response(200, it.put("fixtureDeleted", deleted).put("fixtureCacheRemoved", removed)) }
            ?: error(503, "source_photo_probe_failed")
    }
}
