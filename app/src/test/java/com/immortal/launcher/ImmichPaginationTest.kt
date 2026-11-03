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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mockStatic

class ImmichPaginationTest {
  private fun page(next: Any?, vararg assets: Pair<String, String>): String =
      JSONObject().put("assets", JSONObject()
          .put("items", JSONArray().also { array ->
            assets.forEach { (id, type) ->
              array.put(JSONObject().put("id", id).put("type", type))
            }
          })
          .put("nextPage", next ?: JSONObject.NULL)).toString()

  private fun withServer(
      respond: (Int) -> String,
      block: (String, List<JSONObject>) -> Unit,
  ) {
    val requests = CopyOnWriteArrayList<JSONObject>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    try {
      server.createContext("/api/search/metadata") { exchange ->
        try {
          assertEquals("POST", exchange.requestMethod)
          assertEquals("fixture-key", exchange.requestHeaders.getFirst("x-api-key"))
          val request = JSONObject(exchange.requestBody.use {
            it.readBytes().toString(Charsets.UTF_8)
          })
          requests.add(request)
          val bytes = respond(request.getInt("page")).toByteArray(Charsets.UTF_8)
          exchange.responseHeaders.set("Content-Type", "application/json")
          exchange.sendResponseHeaders(200, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        } finally {
          exchange.close()
        }
      }
      server.start()
      mockStatic(Log::class.java).use {
        block("http://127.0.0.1:${server.address.port}", requests)
      }
    } finally {
      server.stop(0)
    }
  }

  private fun photos(base: String, vararg ids: String): List<ImmichSource.Media> =
      ids.map { ImmichSource.Media("$base/api/assets/$it/thumbnail?size=preview", false) }

  @Test fun forwardStringAndIntegerPagesPreserveOrderAndAlbumFilter() {
    withServer({ number ->
      when (number) {
        1 -> page("2", "a" to "IMAGE")
        2 -> page(3, "b" to "IMAGE")
        else -> page(null, "c" to "IMAGE")
      }
    }) { base, requests ->
      assertEquals(photos(base, "a", "b", "c"),
          ImmichSource.listMedia(base, "fixture-key", "fixture-album", cap = 4))
      assertEquals(listOf(1, 2, 3), requests.map { it.getInt("page") })
      assertTrue(requests.all {
        it.getInt("size") == 1000 && it.getString("type") == "IMAGE" &&
            it.getJSONArray("albumIds").getString(0) == "fixture-album"
      })
    }
  }

  @Test fun repeatedFirstPageStopsAfterOneRequest() {
    withServer({ page(1, "a" to "IMAGE") }) { base, requests ->
      assertEquals(photos(base, "a"),
          ImmichSource.listMedia(base, "fixture-key", null, cap = 4))
      assertEquals(listOf(1), requests.map { it.getInt("page") })
    }
  }

  @Test fun repeatedSecondPageKeepsOnePhotoWithoutRepeatedRequests() {
    withServer({ page(2, "a" to "IMAGE") }) { base, requests ->
      assertEquals(photos(base, "a"),
          ImmichSource.listMedia(base, "fixture-key", null, cap = 4))
      assertEquals(listOf(1, 2), requests.map { it.getInt("page") })
    }
  }

  @Test fun backwardPageKeepsCollectedPhotosWithoutRestarting() {
    withServer({ number ->
      if (number == 1) page(2, "a" to "IMAGE") else page(1, "b" to "IMAGE")
    }) { base, requests ->
      assertEquals(photos(base, "a", "b"),
          ImmichSource.listMedia(base, "fixture-key", null, cap = 4))
      assertEquals(listOf(1, 2), requests.map { it.getInt("page") })
    }
  }

  @Test fun zeroAndNegativePagesAreNeverRequested() {
    for (next in listOf(0, -1)) {
      withServer({ page(next, "a" to "IMAGE") }) { base, requests ->
        assertEquals(photos(base, "a"),
            ImmichSource.listMedia(base, "fixture-key", null, cap = 4))
        assertEquals(listOf(1), requests.map { it.getInt("page") })
      }
    }
  }

  @Test fun duplicateRowsInOnePageDoNotConsumeTheMediaCap() {
    withServer({ page(null, "a" to "IMAGE", "a" to "IMAGE", "b" to "IMAGE") }) {
        base, requests ->
      assertEquals(photos(base, "a", "b"),
          ImmichSource.listMedia(base, "fixture-key", null, cap = 2))
      assertEquals(1, requests.size)
    }
  }

  @Test fun overlappingPagesKeepFirstSeenOrderAndCountUniqueAssets() {
    withServer({ number ->
      when (number) {
        1 -> page(2, "a" to "IMAGE", "b" to "IMAGE")
        2 -> page(3, "b" to "IMAGE", "c" to "IMAGE")
        else -> page(null, "c" to "IMAGE", "d" to "IMAGE")
      }
    }) { base, requests ->
      assertEquals(photos(base, "a", "b", "c", "d"),
          ImmichSource.listMedia(base, "fixture-key", null, cap = 4))
      assertEquals(listOf(1, 2, 3), requests.map { it.getInt("page") })
    }
  }

  @Test fun excludedVideosDoNotConsumeUniqueImageSlots() {
    withServer({
      page(null, "clip" to "VIDEO", "a" to "IMAGE", "a" to "IMAGE", "b" to "IMAGE")
    }) { base, requests ->
      assertEquals(photos(base, "a", "b"),
          ImmichSource.listMedia(base, "fixture-key", null, includeVideo = false, cap = 2))
      assertEquals("IMAGE", requests.single().getString("type"))
      assertFalse(requests.single().has("albumIds"))
    }
  }

  @Test fun includedVideosAreUniqueAndShareTheCapWithImages() {
    withServer({
      page(null, "clip" to "VIDEO", "clip" to "VIDEO", "a" to "IMAGE", "b" to "IMAGE")
    }) { base, requests ->
      assertEquals(listOf(
          ImmichSource.Media("$base/api/assets/clip/video/playback", true),
          photos(base, "a").single(),
          photos(base, "b").single()),
          ImmichSource.listMedia(base, "fixture-key", null, includeVideo = true, cap = 3))
      assertFalse(requests.single().has("type"))
    }
  }

  @Test fun unsupportedAssetsWithAdvancingPagesHaveAFiniteRequestBudget() {
    // The fixture's empty page101 terminates the unguarded baseline without a hung test.
    withServer({ number ->
      if (number > 100) page(null) else page(number + 1, "unknown-$number" to "OTHER")
    }) { base, requests ->
      assertEquals(emptyList<ImmichSource.Media>(),
          ImmichSource.listMedia(base, "fixture-key", null, cap = 4))
      assertEquals((1..100).toList(), requests.map { it.getInt("page") })
    }
  }

  @Test fun emptyAndMalformedNextPagesKeepExistingTermination() {
    for (next in listOf<Any?>(null, "not-a-page", 2.5, true, JSONArray())) {
      withServer({ page(next, "a" to "IMAGE") }) { base, requests ->
        assertEquals(photos(base, "a"),
            ImmichSource.listMedia(base, "fixture-key", null, cap = 4))
        assertEquals(listOf(1), requests.map { it.getInt("page") })
      }
    }
    withServer({ page(2) }) { base, requests ->
      assertEquals(emptyList<ImmichSource.Media>(),
          ImmichSource.listMedia(base, "fixture-key", null, cap = 4))
      assertEquals(listOf(1), requests.map { it.getInt("page") })
    }
  }

  @Test fun zeroCapDoesNotSendAnyRequest() {
    withServer({ page(2, "a" to "IMAGE") }) { base, requests ->
      assertEquals(emptyList<ImmichSource.Media>(),
          ImmichSource.listMedia(base, "fixture-key", null, cap = 0))
      assertTrue(requests.isEmpty())
    }
  }
}
