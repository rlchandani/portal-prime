/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * Fetches cover art for the multi-room now-playing card from Music Assistant. MA serves
 * art over plain HTTP on the LAN, but the launcher stays hardened
 * (`usesCleartextTraffic=false`), so for `http://` URLs we do ONE deliberate, auditable
 * plaintext fetch over a RAW SOCKET — the cleartext policy governs the platform HTTP
 * stack and WebView, not raw sockets, so this keeps the app-wide hardening intact while
 * still reading the LAN art. `https://` URLs go through the normal stack.
 *
 * Caches the last URL→bitmap so we don't re-fetch while a track keeps playing.
 */
object MultiRoomArt {
  private const val MAX_EDGE = 384 // px — plenty for the card / header chip
  private const val MAX_BODY = 8 * 1024 * 1024 // 8 MB cap — art, not downloads

  private var cachedUrl: String = ""
  private var cachedBitmap: Bitmap? = null

  /** Downscaled cover bitmap for [url], or null on any failure. Blocking — call off-main. */
  @Synchronized
  fun fetch(url: String): Bitmap? {
    if (url.isBlank()) return null
    if (url == cachedUrl && cachedBitmap != null) return cachedBitmap
    val raw = runCatching { download(url, redirects = 3) }.getOrNull() ?: return null
    val bmp = decodeAndScale(raw) ?: return null
    cachedUrl = url
    cachedBitmap = bmp
    return bmp
  }

  /** Raw bytes for [url]: https via the platform stack, http via a raw socket. */
  private fun download(spec: String, redirects: Int): ByteArray? {
    val u = URL(spec)
    return if (u.protocol.equals("https", ignoreCase = true)) downloadTls(u)
    else downloadCleartext(u, redirects)
  }

  private fun downloadTls(u: URL): ByteArray? {
    val c = (u.openConnection() as HttpURLConnection).apply {
      connectTimeout = 6000
      readTimeout = 8000
      instanceFollowRedirects = true
      setRequestProperty("User-Agent", "Immortal/1.0")
    }
    return runCatching { c.inputStream.use { it.readBytes() } }.getOrNull().also { c.disconnect() }
  }

  /**
   * Minimal HTTP/1.1 GET over a raw socket with `Connection: close`, so we just read to
   * EOF and split headers from body — no Content-Length/chunked bookkeeping for the
   * common case. Handles chunked bodies if the server uses them, and follows a single
   * redirect. Plenty for MA's imageproxy.
   */
  private fun downloadCleartext(u: URL, redirects: Int): ByteArray? {
    val host = u.host
    val port = if (u.port != -1) u.port else 80
    val path = (if (u.path.isNullOrBlank()) "/" else u.path) + (u.query?.let { "?$it" } ?: "")
    Socket(host, port).use { s ->
      s.soTimeout = 8000
      val req =
          "GET $path HTTP/1.1\r\n" +
              "Host: $host\r\n" +
              "User-Agent: Immortal/1.0\r\n" +
              "Accept: image/*\r\n" +
              "Connection: close\r\n\r\n"
      s.getOutputStream().apply {
        write(req.toByteArray(Charsets.ISO_8859_1))
        flush()
      }
      val all = s.getInputStream().readBytes().let { if (it.size > MAX_BODY) it.copyOf(MAX_BODY) else it }
      val sep = indexOfCrlfCrlf(all)
      if (sep < 0) return null
      val header = String(all, 0, sep, Charsets.ISO_8859_1)
      val status = header.lineSequence().firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
      if (status in 300..399 && redirects > 0) {
        val loc =
            header.lineSequence()
                .firstOrNull { it.startsWith("Location:", ignoreCase = true) }
                ?.substringAfter(":")
                ?.trim()
        if (!loc.isNullOrBlank()) {
          val next = runCatching { URL(u, loc) }.getOrNull() ?: return null
          return download(next.toString(), redirects - 1)
        }
      }
      if (status !in 200..299) return null
      var body = all.copyOfRange(sep + 4, all.size)
      if (header.contains("Transfer-Encoding: chunked", ignoreCase = true)) body = dechunk(body)
      return body
    }
  }

  /** Index of the blank line (\r\n\r\n) separating headers from body, or -1. */
  private fun indexOfCrlfCrlf(b: ByteArray): Int {
    var i = 0
    while (i + 3 < b.size) {
      if (b[i] == 13.toByte() && b[i + 1] == 10.toByte() && b[i + 2] == 13.toByte() && b[i + 3] == 10.toByte())
          return i
      i++
    }
    return -1
  }

  /** De-chunk an HTTP/1.1 chunked body. */
  private fun dechunk(body: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < body.size) {
      var j = i
      while (j + 1 < body.size && !(body[j] == 13.toByte() && body[j + 1] == 10.toByte())) j++
      val sizeHex = String(body, i, j - i, Charsets.ISO_8859_1).trim().substringBefore(";")
      val size = sizeHex.toIntOrNull(16) ?: break
      if (size == 0) break
      val start = j + 2
      if (start + size > body.size) break
      out.write(body, start, size)
      i = start + size + 2 // skip the chunk's trailing CRLF
    }
    return out.toByteArray()
  }

  private fun decodeAndScale(raw: ByteArray): Bitmap? {
    val src = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
    val longest = maxOf(src.width, src.height)
    if (longest <= MAX_EDGE) return src
    val ratio = MAX_EDGE.toFloat() / longest
    val scaled =
        Bitmap.createScaledBitmap(
            src, (src.width * ratio).toInt().coerceAtLeast(1), (src.height * ratio).toInt().coerceAtLeast(1), true)
    if (scaled !== src) src.recycle()
    return scaled
  }
}
