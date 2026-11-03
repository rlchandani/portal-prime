/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

/**
 * Shared HTTP helper for the launcher's many small `HttpURLConnection` fetchers. Before this,
 * ~11 files each hand-rolled the same `URL(spec).openConnection()` boilerplate with slightly
 * different timeouts, User-Agents, and (in)consistent error handling. This consolidates the
 * common cases so a new network-backed widget (crypto, news, stocks, options) is a one-liner.
 *
 * Design (matches the most robust existing callers — Immich/Dav):
 *  - **Nullable returns**: null on non-2xx or failures other than owned Tools cancellation.
 *    Callers keep their own `runCatching`
 *    around JSON parsing but no longer need to guard the network call itself.
 *  - **Header map**: arbitrary request headers (e.g. Alpaca's `APCA-API-KEY-ID`/`APCA-API-SECRET-KEY`,
 *    Immich's `x-api-key`, WebDAV Basic auth). This is why the API takes a `headers` map by default.
 *  - **Defaultable User-Agent / timeouts**: most callers want `Immortal/1.0` + 8s/12s; installers
 *    (APK downloads) want longer reads; Immich sets no UA. `userAgent = null` sends none.
 *  - **Error stream logged**: a rejected request's body is the one diagnostic a user report can
 *    carry, so failures log the server's own words (truncated). Owned Tools cancellation propagates.
 *
 * NOT covered here (intentionally — see docs/superpowers/plans/2026-07-30-advanced-widgets-feasibility.md):
 * SpeedTest's throughput measurement, DavSource's custom `PROPFIND`, and streaming-to-file APK/video
 * downloads. Those need a raw connection/stream handle, not a materialized String/ByteArray.
 */
object Http {

  private const val TAG = "ImmortalHttp"

  /** The launcher's default User-Agent. Pass a different string, or `null` to send none. */
  const val DEFAULT_UA = "Immortal/1.0"

  private const val DEFAULT_CONNECT_MS = 8000
  private const val DEFAULT_READ_MS = 12000

  /**
   * GET [url] and return the decoded UTF-8 body, or null on any non-2xx response or failure.
   * Best-effort except for owned Tools cancellation. Failures log the server's error body.
   */
  fun getString(
      url: String,
      headers: Map<String, String> = emptyMap(),
      connectTimeoutMs: Int = DEFAULT_CONNECT_MS,
      readTimeoutMs: Int = DEFAULT_READ_MS,
      userAgent: String? = DEFAULT_UA,
  ): String? =
      request("GET", url, headers, connectTimeoutMs, readTimeoutMs, userAgent) {
        it.inputStream.use { s -> s.readBytes().toString(Charsets.UTF_8) }
      }

  /** GET [url] and return the raw response bytes (images/binary), or null on non-2xx / failure. */
  fun getBytes(
      url: String,
      headers: Map<String, String> = emptyMap(),
      connectTimeoutMs: Int = DEFAULT_CONNECT_MS,
      readTimeoutMs: Int = DEFAULT_READ_MS,
      userAgent: String? = DEFAULT_UA,
  ): ByteArray? =
      request("GET", url, headers, connectTimeoutMs, readTimeoutMs, userAgent) {
        it.inputStream.use { s -> s.readBytes() }
      }

  /**
   * POST [body] to [url] with the given [contentType], returning the decoded UTF-8 response, or
   * null on non-2xx / failure. Used by JSON APIs that require a request body (e.g. Immich search).
   */
  fun postString(
      url: String,
      body: String,
      contentType: String = "application/json",
      headers: Map<String, String> = emptyMap(),
      connectTimeoutMs: Int = DEFAULT_CONNECT_MS,
      readTimeoutMs: Int = DEFAULT_READ_MS,
      userAgent: String? = DEFAULT_UA,
  ): String? =
      request("POST", url, headers, connectTimeoutMs, readTimeoutMs, userAgent, configure = { c ->
        c.doOutput = true
        c.setRequestProperty("Content-Type", contentType)
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      }) {
        it.inputStream.use { s -> s.readBytes().toString(Charsets.UTF_8) }
      }

  /**
   * The single connection-lifecycle owner: opens [url], applies method/timeouts/UA/headers, runs
   * any extra [configure] (e.g. writing a POST body), then — only on a 2xx — hands the connection
   * to [read]. Failures or non-2xx yield null; owned Tools cancellation propagates. Generic over the
   * body type so String and ByteArray readers share one code path.
   */
  private fun <T> request(
      method: String,
      url: String,
      headers: Map<String, String>,
      connectTimeoutMs: Int,
      readTimeoutMs: Int,
      userAgent: String?,
      configure: (HttpURLConnection) -> Unit = {},
      read: (HttpURLConnection) -> T,
  ): T? {
    ToolsWork.checkpoint()
    var connection: HttpURLConnection? = null
    return try {
      val c = URL(url).openConnection() as HttpURLConnection
      connection = c
      ToolsWork.track(c)
      c.requestMethod = method
      c.connectTimeout = connectTimeoutMs
      c.readTimeout = readTimeoutMs
      c.instanceFollowRedirects = true
      userAgent?.let { c.setRequestProperty("User-Agent", it) }
      headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
      ToolsWork.checkpoint()
      configure(c)
      ToolsWork.checkpoint()
      val result =
          if (c.responseCode in 200..299) read(c)
          else null.also { logFailure(method, url, c) }
      ToolsWork.checkpoint()
      result
    } catch (e: Throwable) {
      ToolsWork.checkpoint()
      if (ToolsWork.ownsCurrentRequest() && e is CancellationException) throw e
      Log.w(TAG, "$method $url -> ${e.javaClass.simpleName}: ${e.message}")
      null
    } finally {
      runCatching { connection?.let(ToolsWork::disconnect) }
    }
  }

  /** Keep the server's own rejection words — the one clue a user report can carry. */
  private fun logFailure(method: String, url: String, c: HttpURLConnection) {
    val detail =
        runCatching {
          c.errorStream?.reader(Charsets.UTF_8)?.use { reader ->
            // Decode only the displayed prefix; the reader owns a fixed read-ahead buffer.
            val chars = CharArray(300)
            var size = 0
            while (size < chars.size) {
              val count = reader.read(chars, size, chars.size - size)
              if (count < 0) break
              size += count
            }
            String(chars, 0, size)
          }
        }
            .getOrNull()
    Log.w(TAG, "$method $url -> HTTP ${c.responseCode}${detail?.let { ": $it" } ?: ""}")
  }
}
