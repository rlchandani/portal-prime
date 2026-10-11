/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Fixed generated-media check. No caller-supplied path and no image/video bytes leave the app. */
internal object SourcePreviewProbe {
    private val running = AtomicBoolean(false)
    private fun response(status: Int, value: JSONObject) = FleetHttpServer.Response(status, value.toString())
    private fun error(status: Int, code: String) = response(status, JSONObject().put("ok", false).put("error", code))

    fun handle(context: Context, request: FleetHttpServer.Request): FleetHttpServer.Response {
        if (!BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) return error(403, "source_preview_probe_disabled")
        if (!DevMode.isEnabled(context)) return error(403, "dev_mode_required")
        if (request.method != "GET") return error(405, "method_not_allowed")
        if (request.query.isNotEmpty()) return error(400, "query_not_supported")
        if (Looper.myLooper() == Looper.getMainLooper()) return error(503, "worker_required")
        if (HomeUiDiagnostics.collectorBusy()) return error(409, "source_preview_perf_busy")
        if (!running.compareAndSet(false, true)) return error(409, "source_preview_probe_busy")
        var file: File? = null
        var preview: Bitmap? = null
        var result: JSONObject? = null
        var deleted = false
        var removed = false
        try {
            file = File.createTempFile("immortal-generated-preview-", ".mp4", context.cacheDir)
            context.assets.open("diagnostics/source-preview-4k.mp4").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val retriever = MediaMetadataRetriever()
            val legacyStart = SystemClock.elapsedRealtimeNanos()
            try {
                retriever.setDataSource(file.path)
                val inputWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val inputHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                check(inputWidth == 3840 && inputHeight == 2160)
                val full = retriever.getFrameAtTime(0) ?: error("generated_frame_unavailable")
                try {
                    result = JSONObject().put("ok", true).put("inputWidth", inputWidth).put("inputHeight", inputHeight)
                        .put("legacyWidth", full.width).put("legacyHeight", full.height)
                        .put("legacyBytes", full.allocationByteCount)
                        .put("legacyDecodeMs", (SystemClock.elapsedRealtimeNanos() - legacyStart) / 1_000_000)
                } finally { full.recycle() }
            } finally { retriever.release() }
            val previewStart = SystemClock.elapsedRealtimeNanos()
            val item = MediaItem(file.path, true)
            preview = approvedSourcesThumbnail(item) ?: error("generated_preview_unavailable")
            val cached = approvedSourcesThumbnail(item)
            result!!.put("previewWidth", preview.width).put("previewHeight", preview.height)
                .put("previewBytes", preview.allocationByteCount).put("previewCacheReused", cached === preview)
                .put("previewDecodeMs", (SystemClock.elapsedRealtimeNanos() - previewStart) / 1_000_000)
        } catch (_: Exception) { result = null }
        finally {
            file?.let {
                val bitmap = approvedSourcesDiscardPreview(MediaItem(it.path, true))
                removed = bitmap === preview
                if (bitmap != null && bitmap !== preview && !bitmap.isRecycled) bitmap.recycle()
                if (preview?.isRecycled == false) preview?.recycle()
                deleted = it.delete()
            }
            running.set(false)
        }
        return result?.let { response(200, it.put("fixtureDeleted", deleted).put("fixtureCacheRemoved", removed)) }
            ?: error(503, "source_preview_probe_failed")
    }
}
