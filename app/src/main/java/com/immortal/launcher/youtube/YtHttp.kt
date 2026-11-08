/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException

/**
 * One HTTP request. Credentials travel only in [headers] (Bearer) or the urlencoded [form] body —
 * never in [url] — and [toString] prints just the method and path so a log line can't leak them.
 */
class YtHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val form: Map<String, String>? = null,
) {
  override fun toString(): String = "$method ${url.substringBefore('?')}"
}

/** Status + body. [toString] omits the body (token responses carry secrets). */
class YtHttpResponse(val code: Int, val body: String) {
  override fun toString(): String = "HTTP $code"
}

/** Network seam. Implementations throw [IOException] when the network is unreachable. */
fun interface YtHttpTransport {
  suspend fun execute(request: YtHttpRequest): YtHttpResponse
}

/** Outcome of one transport call with every exception collapsed to a message-free type. */
internal sealed interface YtExchange {
  data class Answered(val response: YtHttpResponse) : YtExchange
  data object Unreachable : YtExchange
  data object Crashed : YtExchange
}

/**
 * Runs [request] without letting an exception (whose message might echo a URL or header) escape.
 * Cancellation still propagates.
 */
internal suspend fun YtHttpTransport.exchange(request: YtHttpRequest): YtExchange =
    try {
      YtExchange.Answered(execute(request))
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      YtExchange.Unreachable
    } catch (e: Exception) {
      YtExchange.Crashed
    }

internal fun encodeForm(params: Map<String, String>): String =
    params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

internal fun ytUrl(base: String, params: Map<String, String?>): String {
  val query = params.filterValues { it != null }.mapValues { it.value!! }
  return if (query.isEmpty()) base else "$base?${encodeForm(query)}"
}

private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")
