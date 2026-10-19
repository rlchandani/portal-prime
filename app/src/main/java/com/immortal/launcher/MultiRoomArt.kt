/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale

/**
 * Fetches Music Assistant artwork off-main. Plain HTTP uses an explicit raw socket for
 * LAN art while preserving the launcher's app-wide cleartext policy; HTTPS uses the
 * platform stack. Both paths bound reads before decode and reject partial responses.
 */
object MultiRoomArt {
  private const val DEFAULT_EDGE = 384
  private const val MAX_EDGE = 1024
  private const val MAX_CACHE_BYTES = 16 * 1024 * 1024
  private data class ArtKey(val url: String, val maxEdge: Int)
  private val cache = object : LruCache<ArtKey, Bitmap>(MAX_CACHE_BYTES) {
    override fun sizeOf(key: ArtKey, value: Bitmap): Int = value.allocationByteCount
    // Do not recycle on eviction: the UI may still display a returned bitmap.
  }

  /** Card/header thumbnail. Blocking — call off-main. */
  fun fetch(url: String): Bitmap? = fetch(url, DEFAULT_EDGE)

  /** Sampled cover, capped at 1024 px even if a caller requests more. Call off-main. */
  @Synchronized
  fun fetch(url: String, maxEdge: Int): Bitmap? {
    if (url.isBlank() || maxEdge <= 0) return null
    val edge = minOf(maxEdge, MAX_EDGE)
    val key = ArtKey(url, edge)
    cache.get(key)?.let {
      if (!it.isRecycled) return it
      cache.remove(key)
    }
    return runCatching {
      val raw = download(url, redirects = 3) ?: return null
      val bitmap = decodeSampled(raw, edge) ?: return null
      cache.put(key, bitmap)
      bitmap
    }.getOrNull()
  }

  private fun download(spec: String, redirects: Int): ByteArray? {
    val url = URL(spec)
    return when (url.protocol.lowercase(Locale.ROOT)) {
      "https" -> downloadTls(url, redirects)
      "http" -> downloadCleartext(url, redirects)
      else -> null
    }
  }

  private fun downloadTls(url: URL, redirects: Int): ByteArray? {
    val connection = (url.openConnection() as HttpURLConnection).apply {
      connectTimeout = 6000
      readTimeout = 8000
      instanceFollowRedirects = false
      setRequestProperty("User-Agent", "Immortal/1.0")
      setRequestProperty("Accept", "image/*")
    }
    var next: String? = null
    try {
      val status = connection.responseCode
      if (status in 300..399 && redirects > 0) {
        next = connection.getHeaderField("Location")?.takeIf { it.isNotBlank() }
      } else if (status in 200..299) {
        val length = connection.contentLengthLong
        if (length > MusicArtworkPolicy.MAX_BODY_BYTES) return null
        val body = connection.inputStream.use {
          MusicArtworkPolicy.readBounded(it, MusicArtworkPolicy.MAX_BODY_BYTES)
        }
        return body.takeIf { length < 0 || it.size.toLong() == length }
      }
    } finally {
      connection.disconnect()
    }
    return next?.let { download(URL(url, it).toString(), redirects - 1) }
  }

  /** Framed HTTP body reader: it never downloads a whole response before enforcing caps. */
  private fun downloadCleartext(url: URL, redirects: Int): ByteArray? {
    val host = url.host
    val port = if (url.port != -1) url.port else 80
    val path = (if (url.path.isNullOrBlank()) "/" else url.path) +
        (url.query?.let { "?$it" } ?: "")
    if (host.isBlank() || path.any { it == '\r' || it == '\n' } ||
        host.any { it == '\r' || it == '\n' }) return null
    val authority = if (url.port != -1) "$host:$port" else host
    val response = Socket().use { socket ->
      socket.connect(InetSocketAddress(host, port), 6000)
      socket.soTimeout = 8000
      val request = "GET $path HTTP/1.1\r\n" +
          "Host: $authority\r\n" +
          "User-Agent: Immortal/1.0\r\n" +
          "Accept: image/*\r\n" +
          "Connection: close\r\n\r\n"
      socket.getOutputStream().apply {
        write(request.toByteArray(Charsets.ISO_8859_1))
        flush()
      }
      MusicArtworkPolicy.readHttpResponse(socket.getInputStream(), MusicArtworkPolicy.MAX_BODY_BYTES)
    }
    if (response.statusCode in 300..399 && redirects > 0 && !response.location.isNullOrBlank()) {
      return download(URL(url, response.location).toString(), redirects - 1)
    }
    return response.body.takeIf { response.statusCode in 200..299 }
  }

  private fun decodeSampled(raw: ByteArray, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    val sample = MusicArtworkPolicy.sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge) ?: return null
    val options = BitmapFactory.Options().apply {
      inSampleSize = sample
      inScaled = false
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: return null
    if (bitmap.width !in 1..maxEdge || bitmap.height !in 1..maxEdge ||
        bitmap.allocationByteCount.toLong() > maxEdge.toLong() * maxEdge * 4) {
      bitmap.recycle()
      return null
    }
    return bitmap
  }
}
