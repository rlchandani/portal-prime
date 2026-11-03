/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.util.Log
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mockStatic

class PhotoSourceRedirectTest {
  private data class Seen(val method: String, val path: String, val key: String?, val body: String)

  private fun fixture(
      respond: (Seen, String) -> Pair<Int, String?>,
      block: (String, List<Seen>, List<Seen>) -> Unit,
  ) {
    val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val destination = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val sourceRequests = CopyOnWriteArrayList<Seen>()
    val destinationRequests = CopyOnWriteArrayList<Seen>()
    fun capture(exchange: com.sun.net.httpserver.HttpExchange) = Seen(
        exchange.requestMethod, exchange.requestURI.path,
        exchange.requestHeaders.getFirst("x-api-key"),
        exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
    val media = """{"assets":{"items":[{"id":"a","type":"IMAGE"}],"nextPage":null}}"""
    try {
      destination.createContext("/") { exchange ->
        try {
          destinationRequests.add(capture(exchange))
          val bytes = media.toByteArray()
          exchange.sendResponseHeaders(200, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        } finally { exchange.close() }
      }
      source.createContext("/") { exchange ->
        try {
          val request = capture(exchange)
          sourceRequests.add(request)
          val (status, location) = respond(request, "http://127.0.0.1:${destination.address.port}/final")
          location?.let { exchange.responseHeaders.set("Location", it) }
          val bytes = media.toByteArray()
          exchange.sendResponseHeaders(status, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        } finally { exchange.close() }
      }
      destination.start()
      source.start()
      mockStatic(Log::class.java).use {
        block("http://127.0.0.1:${source.address.port}", sourceRequests, destinationRequests)
      }
    } finally {
      source.stop(0)
      destination.stop(0)
    }
  }

  @Test fun directAuthenticatedRequestRetainsItsKey() {
    fixture({ _, _ -> 200 to null }) { base, source, destination ->
      assertTrue(ImmichSource.testConnection(base, "fixture-key"))
      assertEquals(1, source.size)
      assertEquals("fixture-key", source.single().key)
      assertTrue(destination.isEmpty())
    }
  }

  @Test fun sameOriginRelativeRedirectsKeepGetAndAuthentication() {
    for (code in listOf(301, 302, 303, 307, 308)) {
      fixture({ request, _ ->
        if (request.path == "/final") 200 to null else code to "/final"
      }) { base, source, destination ->
        assertTrue("status=$code", ImmichSource.testConnection(base, "fixture-key"))
        assertEquals("status=$code", 2, source.size)
        assertTrue(source.all { it.method == "GET" && it.key == "fixture-key" })
        assertTrue(destination.isEmpty())
      }
    }
  }

  @Test fun authenticatedRedirectToAnotherPortNeverContactsThatOrigin() {
    for (code in listOf(301, 302, 303, 307, 308)) {
      fixture({ _, foreign -> code to foreign }) { base, source, destination ->
        assertFalse("status=$code", ImmichSource.testConnection(base, "fixture-key"))
        assertEquals(1, source.size)
        assertTrue("status=$code: destination contacted", destination.isEmpty())
      }
    }
  }

  @Test fun authenticatedRedirectToAnotherHostnameNeverContactsThatOrigin() {
    fixture({ _, foreign -> 302 to foreign.replace("127.0.0.1", "localhost") }) {
        base, source, destination ->
      assertFalse(ImmichSource.testConnection(base, "fixture-key"))
      assertEquals(1, source.size)
      assertTrue(destination.isEmpty())
    }
  }

  @Test fun sameOriginLoopStopsAfterFiveRedirects() {
    fixture({ _, _ -> 302 to "/loop" }) { base, source, destination ->
      assertFalse(ImmichSource.testConnection(base, "fixture-key"))
      assertEquals(6, source.size)
      assertTrue(destination.isEmpty())
    }
  }

  @Test fun missingLocationFailsWithoutAnotherRequest() {
    fixture({ _, _ -> 302 to null }) { base, source, destination ->
      assertFalse(ImmichSource.testConnection(base, "fixture-key"))
      assertEquals(1, source.size)
      assertTrue(destination.isEmpty())
    }
  }

  @Test fun post307And308PreserveBodyMethodAndKey() {
    for (code in listOf(307, 308)) {
      fixture({ request, _ ->
        if (request.path == "/final") 200 to null else code to "/final"
      }) { base, source, destination ->
        val result = ImmichSource.listMedia(base, "fixture-key", "fixture-album", cap = 1)
        assertEquals("status=$code", listOf(ImmichSource.Media(
            "$base/api/assets/a/thumbnail?size=preview", false)), result)
        assertEquals(2, source.size)
        assertTrue(source.all { it.method == "POST" && it.key == "fixture-key" })
        assertEquals(source[0].body, source[1].body)
        assertEquals("fixture-album", JSONObject(source[1].body).getJSONArray("albumIds").getString(0))
        assertTrue(destination.isEmpty())
      }
    }
  }

  @Test fun postRedirectNeverSendsMetadataOrKeyToAnotherOrigin() {
    fixture({ _, foreign -> 307 to foreign }) { base, source, destination ->
      assertTrue(ImmichSource.listMedia(base, "fixture-key", "fixture-album", cap = 1).isNullOrEmpty())
      assertEquals(1, source.size)
      assertTrue(destination.isEmpty())
    }
  }

  @Test fun post301302303CannotSilentlyBecomeGet() {
    for (code in listOf(301, 302, 303)) {
      fixture({ request, _ ->
        if (request.path == "/final") 200 to null else code to "/final"
      }) { base, source, destination ->
        assertTrue("status=$code", ImmichSource.listMedia(base, "fixture-key", null, cap = 1).isNullOrEmpty())
        assertEquals(1, source.size)
        assertTrue(destination.isEmpty())
      }
    }
  }

  @Test fun userInfoAndFragmentInInitialUrlNeverContactTheServer() {
    fixture({ _, _ -> 200 to null }) { base, source, destination ->
      assertFalse(ImmichSource.testConnection(base.replace("http://", "http://user:fixture@"), "fixture-key"))
      assertFalse(ImmichSource.testConnection("$base/#fragment", "fixture-key"))
      assertTrue(source.isEmpty())
      assertTrue(destination.isEmpty())
    }
  }

  @Test fun publicGetRetainsCrossOriginRedirectWithoutAddingAuthentication() {
    fixture({ _, foreign -> 308 to foreign }) { base, source, destination ->
      val connection = PhotoSourceRedirects.open("$base/public", emptyMap())
      try {
        assertTrue(PhotoSourceHttp.readText(connection).contains("\"assets\""))
        assertEquals(1, source.size)
        assertEquals(1, destination.size)
        assertEquals(null, destination.single().key)
      } finally { connection.disconnect() }
    }
  }

  @Test fun eachHopIsOwnedBeforeConnectAndIntermediateHopIsReleased() {
    fixture({ request, _ ->
      if (request.path == "/final") 200 to null else 307 to "/final"
    }) { base, source, _ ->
      val owned = mutableListOf<HttpURLConnection>()
      val released = mutableListOf<HttpURLConnection>()
      val connection = PhotoSourceRedirects.open("$base/start", mapOf("x-api-key" to "fixture-key"),
          opened = { owned.add(it) }, closed = { released.add(it) })
      try {
        assertEquals(2, owned.size)
        assertEquals(listOf(owned.first()), released)
        assertTrue(owned.last() === connection)
        assertEquals(2, source.size)
      } finally { connection.disconnect() }
    }
  }

  @Test fun cancellationDuringOwnershipReleasesConnectionBeforeAnyRequest() {
    fixture({ _, _ -> 200 to null }) { base, source, destination ->
      val released = mutableListOf<HttpURLConnection>()
      try {
        PhotoSourceRedirects.open(base, emptyMap(),
            opened = { throw InterruptedIOException("fixture canceled") },
            closed = { released.add(it) })
        fail("Canceled ownership must abort before connecting")
      } catch (_: InterruptedIOException) {}
      assertEquals(1, released.size)
      assertTrue(source.isEmpty())
      assertTrue(destination.isEmpty())
    }
  }

  @Test fun rejectedDestinationReleasesItsSourceHop() {
    fixture({ _, foreign -> 302 to foreign }) { base, source, destination ->
      val released = mutableListOf<HttpURLConnection>()
      try {
        PhotoSourceRedirects.open(base, mapOf("x-api-key" to "fixture-key"),
            closed = { released.add(it) })
        fail("A different credential origin must be rejected")
      } catch (_: IOException) {}
      assertEquals(1, released.size)
      assertEquals(1, source.size)
      assertTrue(destination.isEmpty())
    }
  }

  @Test fun originIdentityIncludesSchemeHostnameAndEffectivePort() {
    assertTrue(PhotoSourceRedirects.sameOrigin(URL("http://photos.example/a"), URL("http://PHOTOS.example:80/b")))
    assertTrue(PhotoSourceRedirects.sameOrigin(URL("https://photos.example/a"), URL("https://photos.example:443/b")))
    assertFalse(PhotoSourceRedirects.sameOrigin(URL("http://photos.example"), URL("https://photos.example")))
    assertFalse(PhotoSourceRedirects.sameOrigin(URL("https://photos.example"), URL("https://photos.example:444")))
    assertFalse(PhotoSourceRedirects.sameOrigin(URL("https://photos.example"), URL("https://other.example")))
  }

  @Test fun targetPolicyRejectsDowngradeUserInfoFragmentAndNonHttpSchemes() {
    val original = URL("https://photos.example/folder/start")
    for (destination in listOf(
        "http://photos.example/final", "https://user:fixture@photos.example/final",
        "/final#fragment", "ftp://photos.example/final", "file:///tmp/photo.jpg")) {
      try {
        PhotoSourceRedirects.redirectTarget(original, original, destination, false)
        fail("Unsafe target accepted: $destination")
      } catch (_: IOException) {}
    }
    assertEquals("https://photos.example/final?size=preview",
        PhotoSourceRedirects.redirectTarget(original, original, "../final?size=preview", true).toString())
    assertEquals("https://cdn.example/final",
        PhotoSourceRedirects.redirectTarget(original, original, "https://cdn.example/final", false).toString())
  }
}
