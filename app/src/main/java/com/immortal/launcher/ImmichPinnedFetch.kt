/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The album picker fetch for a phone-typed Immich URL, connected to the exact addresses
 * [LanUrlPolicy.immichAlbumsTarget] checked ([LanUrlPolicy.Target.Pinned]). There is no second DNS
 * lookup: the client's [Dns] answers the checked host with the pinned addresses and refuses every
 * other name, and no proxy is used (a proxy would resolve the name itself). The URL keeps the
 * host name, so the `Host` header, TLS SNI and certificate check all use the name the phone typed.
 * Redirects stay on the credential's origin and never downgrade ([PhotoSourceRedirects]); the
 * reply is capped at `maxBytes`.
 */
internal object ImmichPinnedFetch {
  private const val TAG = "ImmortalImmich"
  private const val MAX_REDIRECTS = 5
  private const val CONNECT_TIMEOUT_MS = 8_000L
  private const val READ_TIMEOUT_MS = 10_000L
  private const val BUFFER_BYTES = 8192
  private val redirects = setOf(301, 302, 303, 307, 308)

  /** Shared dispatcher and pool; each fetch adds its own pinned [Dns], so connections never mix. */
  private val base: OkHttpClient by lazy { OkHttpClient() }

  /** The albums, or null on any failure (logged without the URL or key). */
  fun listAlbums(url: String, apiKey: String, target: LanUrlPolicy.Target.Pinned, maxBytes: Int, budgetMs: Long): List<ImmichSource.Album>? =
      runCatching { fetchAlbums(url, apiKey, target, maxBytes, budgetMs) }
          .onFailure { Log.w(TAG, "pinned album list failed: ${it.javaClass.simpleName}") }
          .getOrNull()

  /** As [listAlbums], throwing instead (for tests). */
  internal fun fetchAlbums(
      url: String,
      apiKey: String,
      target: LanUrlPolicy.Target.Pinned,
      maxBytes: Int,
      budgetMs: Long,
  ): List<ImmichSource.Album> {
    val spec = "${ImmichSource.normalizeBase(url)}/api/albums"
    return ImmichSource.parseAlbums(get(spec, ImmichSource.authHeaders(apiKey), target, maxBytes, budgetMs))
  }

  internal fun get(spec: String, headers: Map<String, String>, target: LanUrlPolicy.Target.Pinned, maxBytes: Int, budgetMs: Long): String {
    val client = client(target, budgetMs)
    val original = URL(spec)
    if (!original.host.trim('[', ']').equals(target.host, ignoreCase = true)) throw IOException("URL is not the checked host")
    var current = original
    for (hop in 0..MAX_REDIRECTS) {
      if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Immich fetch interrupted")
      val request = Request.Builder().url(current).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
      client.newCall(request).execute().use { response ->
        if (response.code !in redirects) {
          if (!response.isSuccessful) throw IOException("Immich replied ${response.code}")
          val body = response.body ?: throw IOException("Immich reply has no body")
          if (body.contentLength() > maxBytes) throw IOException("Immich reply exceeds its budget")
          return body.byteStream().use { readCapped(it, maxBytes) }
        }
        if (hop == MAX_REDIRECTS) throw IOException("Immich redirect limit exceeded")
        val location = response.header("Location")?.takeIf { it.isNotBlank() }
            ?: throw IOException("Immich redirect has no destination")
        current = PhotoSourceRedirects.redirectTarget(original, current, location, authenticated = true)
      }
    }
    throw IOException("Immich redirect limit exceeded")
  }

  private fun client(target: LanUrlPolicy.Target.Pinned, budgetMs: Long): OkHttpClient =
      base.newBuilder()
          .dns(PinnedDns(target))
          .proxy(Proxy.NO_PROXY)
          .followRedirects(false)
          .followSslRedirects(false)
          .retryOnConnectionFailure(false)
          .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
          .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
          .callTimeout(budgetMs, TimeUnit.MILLISECONDS)
          .build()

  private fun readCapped(input: InputStream, maxBytes: Int): String {
    val out = ByteArrayOutputStream(minOf(BUFFER_BYTES, maxBytes))
    val buffer = ByteArray(BUFFER_BYTES)
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      if (out.size() + count > maxBytes) throw IOException("Immich reply exceeds its budget")
      out.write(buffer, 0, count)
    }
    return out.toString(Charsets.UTF_8.name())
  }

  /** Answers only the checked host, only with the checked addresses. Never queries DNS. */
  internal class PinnedDns(private val target: LanUrlPolicy.Target.Pinned) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
      if (!hostname.trim('[', ']').equals(target.host, ignoreCase = true)) throw UnknownHostException("not the checked host")
      return target.addresses
    }
  }
}
