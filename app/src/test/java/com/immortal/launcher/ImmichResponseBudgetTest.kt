/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.util.Log
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Real TCP/provider entry points, generated JSON only; no saved source credentials. */
class ImmichResponseBudgetTest {
  @Test fun oversizedKnownAlbumMetadataCannotBecomeAUsableLibrary() = album(true)
  @Test fun oversizedChunkedAlbumMetadataCannotBecomeAUsableLibrary() = album(false)
  @Test fun oversizedKnownSearchMetadataCannotBecomePlayableMedia() = search(true)
  @Test fun oversizedChunkedSearchMetadataCannotBecomePlayableMedia() = search(false)

  private fun album(known: Boolean) = withServer(known, "[{\"id\":\"synthetic\",\"albumName\":\"Generated\",\"assetCount\":1}]", oversized = true) { base, requests ->
    assertNull("Oversized metadata must use the existing source-failure path", ImmichSource.listAlbums(base, "fixture-only"))
    assertEquals(listOf("GET /api/albums"), requests.toList())
  }

  private fun search(known: Boolean) = withServer(known, "{\"assets\":{\"items\":[{\"id\":\"synthetic-photo\",\"type\":\"IMAGE\"}],\"nextPage\":null}}", oversized = true) { base, requests ->
    assertNull("A complete JSON prefix does not make an oversized body acceptable", ImmichSource.listMedia(base, "fixture-only", null))
    assertEquals(listOf("POST /api/search/metadata"), requests.toList())
  }

  @Test fun smallUtf8AlbumsRemainUsableAndKeepTheApiHeader() = withServer(true,
      "[{\"id\":\"synthetic\",\"albumName\":\"Café 日本 🌙\",\"assetCount\":2}]", false) { base, _ ->
    assertEquals(listOf(ImmichSource.Album("synthetic", "Café 日本 🌙", 2)), ImmichSource.listAlbums(base, "fixture-only"))
  }

  @Test fun smallChunkedSearchStillResolvesExistingPreviewUrls() = withServer(false,
      "{\"assets\":{\"items\":[{\"id\":\"synthetic-photo\",\"type\":\"IMAGE\"}],\"nextPage\":null}}", false) { base, _ ->
    assertEquals(listOf(ImmichSource.Media("$base/api/assets/synthetic-photo/thumbnail?size=preview", false)), ImmichSource.listMedia(base, "fixture-only", null))
  }

  private fun withServer(known: Boolean, prefix: String, oversized: Boolean,
      check: (String, CopyOnWriteArrayList<String>) -> Unit) {
    val requests = CopyOnWriteArrayList<String>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/") { exchange ->
      try {
        requests.add("${exchange.requestMethod} ${exchange.requestURI.path}")
        assertEquals("fixture-only", exchange.requestHeaders.getFirst("x-api-key"))
        exchange.requestBody.use { it.readBytes() }
        val bytes = prefix.toByteArray(Charsets.UTF_8)
        val padding = if (oversized) LIMIT + 1 - bytes.size else 0
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, if (known) (bytes.size + padding).toLong() else 0L)
        try {
          exchange.responseBody.use { output ->
            output.write(bytes)
            val spaces = ByteArray(8192) { 32 }
            var remaining = padding
            while (remaining > 0) {
              val count = minOf(spaces.size, remaining)
              output.write(spaces, 0, count)
              remaining -= count
            }
          }
        } catch (_: java.io.IOException) { /* Rejection closes the connection early. */ }
      } finally { exchange.close() }
    }
    server.start()
    try { Mockito.mockStatic(Log::class.java).use { check("http://127.0.0.1:${server.address.port}", requests) } }
    finally { server.stop(0) }
  }

  private companion object { const val LIMIT = 16 * 1024 * 1024 }
}
