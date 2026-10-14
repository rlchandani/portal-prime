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
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class ImmichLibraryRecoveryTest {

  @Test
  fun authenticatedLibraryRemainsAvailableWhenAlbumDiscoveryFails() {
    withServer({ request ->
      when (request.path) {
        "/api/users/me" -> Response(200, """{"id":"fixture-user"}""")
        "/api/albums" -> Response(503, """{"message":"Albums temporarily unavailable"}""")
        "/api/search/metadata" -> Response(200, """{"assets":{"items":[{"id":"photo-one","type":"IMAGE"},{"id":"photo-two","type":"IMAGE"}],"nextPage":null}}""")
        else -> Response(404, "{}")
      }
    }) { base, requests ->
      assertTrue(ImmichSource.testConnection(base, API_KEY))
      assertNull(ImmichSource.listAlbums(base, API_KEY))
      assertEquals(
          listOf(
              ImmichSource.Media("$base/api/assets/photo-one/thumbnail?size=preview", false),
              ImmichSource.Media("$base/api/assets/photo-two/thumbnail?size=preview", false)),
          ImmichSource.listMedia(base, API_KEY, albumId = null))

      assertEquals(
          listOf("/api/users/me", "/api/albums", "/api/search/metadata"),
          requests.map { it.path })
      assertEquals(listOf("GET", "GET", "POST"), requests.map { it.method })
      assertTrue(requests.all { it.apiKey == API_KEY })
      val metadata = requests.last()
      assertEquals("application/json", metadata.contentType)
      val body = JSONObject(metadata.body)
      assertFalse("Whole-library search must not be filtered by album", body.has("albumIds"))
      assertEquals("IMAGE", body.getString("type"))
      assertEquals(1, body.getInt("page"))
      assertEquals(1000, body.getInt("size"))
    }
  }

  @Test
  fun albumMediaPreservesAlbumIds() {
    withServer({ request ->
      if (request.path == "/api/search/metadata") {
        Response(200, """{"assets":{"items":[{"id":"album-photo","type":"IMAGE"}],"nextPage":null}}""")
      } else Response(404, "{}")
    }) { base, requests ->
      assertEquals(
          listOf(ImmichSource.Media("$base/api/assets/album-photo/thumbnail?size=preview", false)),
          ImmichSource.listMedia(base, API_KEY, albumId = "fixture-album"))

      val metadata = requests.single()
      assertEquals("/api/search/metadata", metadata.path)
      assertEquals("POST", metadata.method)
      assertEquals(API_KEY, metadata.apiKey)
      val body = JSONObject(metadata.body)
      val albumIds = body.getJSONArray("albumIds")
      assertEquals(1, albumIds.length())
      assertEquals("fixture-album", albumIds.getString(0))
      assertEquals("IMAGE", body.getString("type"))
    }
  }

  @Test
  fun failedAlbumsCanBeRetriedWithoutRepeatingAuthentication() {
    val albumAttempts = AtomicInteger()
    withServer({ request ->
      when (request.path) {
        "/api/users/me" -> Response(200, """{"id":"fixture-user"}""")
        "/api/albums" ->
            if (albumAttempts.incrementAndGet() == 1) {
              Response(503, """{"message":"Try again"}""")
            } else {
              Response(200, """[{"id":"recovered-album","albumName":"Recovered photos","assetCount":7}]""")
            }
        else -> Response(404, "{}")
      }
    }) { base, requests ->
      assertTrue(ImmichSource.testConnection(base, API_KEY))
      assertNull(ImmichSource.listAlbums(base, API_KEY))
      assertEquals(
          listOf(ImmichSource.Album("recovered-album", "Recovered photos", 7)),
          ImmichSource.listAlbums(base, API_KEY))

      assertEquals(2, albumAttempts.get())
      assertEquals(
          listOf("/api/users/me", "/api/albums", "/api/albums"),
          requests.map { it.path })
      assertTrue(requests.all { it.method == "GET" && it.apiKey == API_KEY })
    }
  }

  @Test
  fun emptySuccessfulAlbumsIsAValidList() {
    withServer({ request ->
      if (request.path == "/api/albums") Response(200, "[]") else Response(404, "{}")
    }) { base, requests ->
      assertEquals(emptyList<ImmichSource.Album>(), ImmichSource.listAlbums(base, API_KEY))
      val request = requests.single()
      assertEquals("/api/albums", request.path)
      assertEquals("GET", request.method)
      assertEquals(API_KEY, request.apiKey)
    }
  }

  @Test
  fun unauthenticatedUserReturnsFalse() {
    withServer({ request ->
      if (request.path == "/api/users/me") {
        Response(401, """{"message":"Unauthorized"}""")
      } else Response(404, "{}")
    }) { base, requests ->
      assertFalse(ImmichSource.testConnection(base, API_KEY))
      val request = requests.single()
      assertEquals("/api/users/me", request.path)
      assertEquals("GET", request.method)
      assertEquals(API_KEY, request.apiKey)
    }
  }

  private data class Request(
      val method: String,
      val path: String,
      val apiKey: String?,
      val contentType: String?,
      val body: String,
  )

  private data class Response(val status: Int, val body: String)

  private fun withServer(
      respond: (Request) -> Response,
      block: (String, List<Request>) -> Unit,
  ) {
    val requests = CopyOnWriteArrayList<Request>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    try {
      server.createContext("/api/") { exchange ->
        try {
          val request = Request(
              exchange.requestMethod,
              exchange.requestURI.path,
              exchange.requestHeaders.getFirst("x-api-key"),
              exchange.requestHeaders.getFirst("Content-Type"),
              exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
          requests.add(request)
          val response = respond(request)
          val bytes = response.body.toByteArray(Charsets.UTF_8)
          exchange.responseHeaders.set("Content-Type", "application/json")
          exchange.sendResponseHeaders(response.status, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        } finally {
          exchange.close()
        }
      }
      server.start()
      val base = "http://127.0.0.1:${server.address.port}"
      // Android Log stubs throw on the HTTP failure paths unless statically mocked.
      Mockito.mockStatic(Log::class.java).use { block(base, requests) }
    } finally {
      server.stop(0)
    }
  }

  private companion object {
    const val API_KEY = "fixture-key"
  }
}
