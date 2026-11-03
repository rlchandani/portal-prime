/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Explicit photo HTTP hops keep source credentials and cancellation on the intended origin. */
internal object PhotoSourceRedirects {
  private const val MAX_REDIRECTS = 5
  private val redirects = setOf(301, 302, 303, 307, 308)

  /**
   * Return an open final response; the caller owns its stream and final disconnection.
   * [opened] runs before connecting each hop, and [closed] releases rejected/intermediate hops.
   * [configure] must be replayable: POST bodies/custom methods are retained only for307/308.
   */
  fun open(
      spec: String,
      headers: Map<String, String>,
      connectTimeoutMs: Int = 8000,
      readTimeoutMs: Int = 12000,
      configure: (HttpURLConnection) -> Unit = {},
      opened: (HttpURLConnection) -> Unit = {},
      closed: (HttpURLConnection) -> Unit = {},
  ): HttpURLConnection {
    val original = validated(spec)
    var current = original
    for (hop in 0..MAX_REDIRECTS) {
      checkpoint()
      val connection = current.openConnection() as HttpURLConnection
      var retained = false
      try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        opened(connection)
        checkpoint()
        configure(connection)
        checkpoint()
        val status = connection.responseCode
        checkpoint()
        if (status !in redirects) {
          retained = true
          return connection
        }
        if (hop == MAX_REDIRECTS) throw IOException("Photo redirect limit exceeded")
        val method = connection.requestMethod
        if (method != "GET" && method != "HEAD" && status != 307 && status != 308)
          throw IOException("Photo redirect would change the request method")
        val location = connection.getHeaderField("Location")?.takeIf { it.isNotBlank() }
            ?: throw IOException("Photo redirect has no destination")
        current = redirectTarget(original, current, location, headers.isNotEmpty())
      } finally {
        if (!retained) {
          runCatching { closed(connection) }
          runCatching { connection.disconnect() }
        }
      }
    }
    throw IOException("Photo redirect limit exceeded")
  }

  internal fun sameOrigin(first: URL, second: URL): Boolean =
      first.protocol.equals(second.protocol, true) &&
          first.host.equals(second.host, true) &&
          effectivePort(first) == effectivePort(second)

  internal fun redirectTarget(
      original: URL, current: URL, location: String, authenticated: Boolean,
  ): URL {
    val next = validated(URL(current, location).toString())
    if (current.protocol.equals("https", true) && next.protocol.equals("http", true))
      throw IOException("Photo redirect would downgrade transport security")
    if (authenticated && !sameOrigin(original, next))
      throw IOException("Photo redirect would leave the credential origin")
    return next
  }

  private fun effectivePort(url: URL): Int = if (url.port >= 0) url.port else url.defaultPort

  private fun validated(spec: String): URL {
    val uri = try { URI(spec) } catch (_: Exception) {
      throw IOException("Invalid photo HTTP URL")
    }
    if ((!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) ||
        uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null ||
        uri.port !in -1..65535)
      throw IOException("Invalid photo HTTP URL")
    return uri.toURL()
  }

  private fun checkpoint() {
    if (Thread.currentThread().isInterrupted)
      throw InterruptedIOException("Photo redirect interrupted")
    PhotoSourceWork.checkpoint()
  }
}
