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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito

class ImmichAlbumIdentityTest {
  private val first = ImmichSource.Album("first", "First", 3)
  private val last = ImmichSource.Album("last", "Last", 8)
  private val firstJson = """{"id":"first","albumName":"First","assetCount":3}"""
  private val lastJson = """{"id":"last","albumName":"Last","assetCount":8}"""

  @Test fun nonObjectRowsKeepValidNeighbors() {
    albums("[$firstJson,null,42,false,[],\"bad\",$lastJson]") {
      assertEquals(listOf(first, last), it)
    }
  }

  @Test fun missingNullAndNonStringIdsKeepValidNeighbors() {
    albums("[$firstJson,{}, {\"id\":null}, {\"id\":42}, {\"id\":false}, {\"id\":[]}, {\"id\":{}},$lastJson]") {
      assertEquals(listOf(first, last), it)
    }
  }

  @Test fun blankIdsKeepValidNeighbors() {
    albums("[$firstJson,{\"id\":\"\"},{\"id\":\" \\t\\n\"},$lastJson]") {
      assertEquals(listOf(first, last), it)
    }
  }

  @Test fun duplicateIdsKeepFirstDetailsAndStableOrder() {
    albums("[$firstJson,$lastJson,{\"id\":\"first\",\"albumName\":\"Replacement\",\"assetCount\":99},$lastJson]") {
      assertEquals(listOf(first, last), it)
      assertEquals(it!!.size, it.map { album -> "album:${album.id}" }.toSet().size)
    }
  }

  @Test fun allInvalidRowsProduceSuccessfulEmptyList() {
    albums("""[null,{},{"id":null},{"id":42},{"id":""},{"id":" "}]""") {
      assertEquals(emptyList<ImmichSource.Album>(), it)
    }
  }

  @Test fun validUnicodeAndDefaultMetadataRemain() {
    albums("""[{"id":"café 日本 🌙","albumName":"Café 日本 🌙","assetCount":7},{"id":"defaults"},{"id":" first "}]""") {
      assertEquals(listOf(
          ImmichSource.Album("café 日本 🌙", "Café 日本 🌙", 7),
          ImmichSource.Album("defaults", "Album", 0),
          ImmichSource.Album(" first ", "Album", 0)), it)
    }
  }

  @Test fun invalidTopLevelKeepsFailureResult() {
    albums("""{"not":"an album array"}""") { assertNull(it) }
  }

  @Test fun rejectedHttpKeepsFailureResult() {
    albums("""{"message":"unavailable"}""", status = 503) { assertNull(it) }
  }

  private fun albums(body: String, status: Int = 200, check: (List<ImmichSource.Album>?) -> Unit) {
    val requests = CopyOnWriteArrayList<List<String?>>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    try {
      server.createContext("/") { exchange ->
        try {
          requests.add(listOf(exchange.requestMethod, exchange.requestURI.path,
              exchange.requestHeaders.getFirst("x-api-key")))
          val bytes = body.toByteArray(Charsets.UTF_8)
          exchange.responseHeaders.set("Content-Type", "application/json")
          exchange.sendResponseHeaders(status, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        } finally {
          exchange.close()
        }
      }
      server.start()
      Mockito.mockStatic(Log::class.java).use {
        val result = ImmichSource.listAlbums("http://127.0.0.1:${server.address.port}", " fixture-key ")
        assertEquals(listOf(listOf("GET", "/api/albums", "fixture-key")), requests.toList())
        check(result)
      }
    } finally {
      server.stop(0)
    }
  }
}
