/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** Cancellation persists before execute; bodies stream into private, bounded partial files. */
internal object EngineRemoteHttp {
  private val client = OkHttpClient.Builder()
      .connectTimeout(8, TimeUnit.SECONDS)
      .readTimeout(12, TimeUnit.SECONDS)
      .callTimeout(20, TimeUnit.SECONDS)
      .build()

  fun download(url: String, headers: Map<String, String>, userAgent: String,
      target: File, owner: EnginePhotoTransports, current: () -> Boolean): Boolean {
    val request = Request.Builder().url(url).header("User-Agent", userAgent).apply {
      headers.forEach { (key, value) -> header(key, value) }
    }.build()
    val call = client.newCall(request)
    val lease = owner.register { call.cancel() } ?: return false
    fun valid() = lease.isCurrent() && current() && !Thread.currentThread().isInterrupted
    return try {
      if (!valid()) false else call.execute().use { response ->
        val body = response.body
        if (!response.isSuccessful || body == null || !valid()) false
        else body.byteStream().use { input ->
          target.outputStream().use { output -> EnginePhotoTransfer.copy(input, output, current = ::valid) }
        }
      }
    } catch (_: java.io.IOException) {
      false
    } finally {
      lease.close()
    }
  }
}
