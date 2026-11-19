/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Camera snapshots for the phone: this Portal fetches HA's `camera_proxy` with its own token and
 * hands back only the JPEG bytes, so the phone never holds the HA token or a camera access token.
 * Never cached, never stored, never logged (only a short camera hash). The phone judges liveness
 * from the bytes themselves, exactly as the Portal does ([CcCamLiveness]).
 */
internal object CcRemoteCamera {
  class Shot(val bytes: ByteArray, val captured: Long?)

  const val MAX_BYTES = 1_500_000
  const val MIN_WIDTH = 160
  const val MAX_WIDTH = 1280
  private const val TAG = "HearthpaneCamera"
  private const val CONNECT_S = 6L
  private const val READ_S = 8L
  private val client by lazy {
    OkHttpClient.Builder().connectTimeout(CONNECT_S, TimeUnit.SECONDS).readTimeout(READ_S, TimeUnit.SECONDS)
        .callTimeout(READ_S + CONNECT_S, TimeUnit.SECONDS).cache(null).followRedirects(false).build()
  }

  /** Null when HA isn't set up, refused, or sent something that isn't a reasonably sized picture. */
  fun fetch(context: Context, cameraId: String, width: Int, attrs: Map<String, Any?>): Shot? {
    if (!HaConfig.isConfigured(context)) return null
    val url = CcCameraLogic.freshSnapshotUrl(HaConfig.baseUrl(context), cameraId, System.currentTimeMillis(), width.coerceIn(MIN_WIDTH, MAX_WIDTH))
    val request = runCatching {
      Request.Builder().url(url).header("Cache-Control", "no-cache").header("Pragma", "no-cache")
          .apply { HaConfig.authHeaders(context).forEach { (k, v) -> header(k, v) } }.build()
    }.getOrNull() ?: return null
    return runCatching {
      client.newCall(request).execute().use { r ->
        val body = r.body
        if (!r.isSuccessful || body == null) { Log.i(TAG, "phone snapshot HTTP ${r.code} (${CcCameraLogic.logId(cameraId)})"); return null }
        if (body.contentLength() > MAX_BYTES) return null
        val bytes = body.byteStream().use { it.readNBytesCompat(MAX_BYTES + 1) }
        if (bytes.size > MAX_BYTES || !looksLikeImage(bytes)) return null
        Shot(bytes, CcCamLiveness.capturedAt(attrs) ?: r.headers.getDate("Last-Modified")?.time)
      }
    }.onFailure { Log.i(TAG, "phone snapshot failed: ${it.javaClass.simpleName} (${CcCameraLogic.logId(cameraId)})") }.getOrNull()
  }

  /** JPEG or PNG magic: HA error pages are never passed on as pictures. */
  fun looksLikeImage(b: ByteArray): Boolean =
      b.size > 3 && (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() ||
          b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte())

  private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(BUFFER)
    while (out.size() < limit) {
      val n = read(buf, 0, minOf(buf.size, limit - out.size()))
      if (n < 0) break
      out.write(buf, 0, n)
    }
    return out.toByteArray()
  }

  private const val BUFFER = 16 * 1024
}
