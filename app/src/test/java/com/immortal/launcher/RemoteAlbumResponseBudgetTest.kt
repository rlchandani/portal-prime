/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteAlbumResponseBudgetTest {
    private enum class ResponseMode { ORDINARY, OVERSIZED_LANDING, OVERSIZED_ALBUM, OVERSIZED_LATER_ITEMS }

    private data class Request(
        val method: String,
        val path: String,
        val params: Map<String, String>,
        val cookie: String?,
    )

    private class Fixture(private val mode: ResponseMode) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin = "http://127.0.0.1:${server.address.port}"
        val shareUrl = "$origin/mo/sharing/fixture"
        val requests = CopyOnWriteArrayList<Request>()

        init {
            server.createContext("/mo/sharing/") { exchange ->
                val params = decodeQuery(exchange.requestURI.rawQuery.orEmpty())
                requests.add(Request(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    params,
                    exchange.requestHeaders.getFirst("Cookie"),
                ))
                try {
                    when (exchange.requestURI.path) {
                        "/mo/sharing/fixture" -> {
                            exchange.responseHeaders.add(
                                "Set-Cookie", "sharing_sid=fixture; Path=/mo/sharing/; HttpOnly",
                            )
                            reply(
                                exchange,
                                "<html><body>Fixture album</body></html>",
                                oversized = mode == ResponseMode.OVERSIZED_LANDING,
                            )
                        }
                        "/mo/sharing/webapi/entry.cgi" -> when (params["api"]) {
                            "\"SYNO.Foto.Browse.Album\"" -> reply(
                                exchange,
                                """{"success":true,"data":{"list":[{"name":"Fixture album","sort_by":"takentime","sort_direction":"asc"}]}}""",
                                oversized = mode == ResponseMode.OVERSIZED_ALBUM,
                            )
                            "\"SYNO.Foto.Browse.Item\"" -> {
                                val laterPage = params["offset"] != "0"
                                val body = if (mode == ResponseMode.OVERSIZED_LATER_ITEMS) {
                                    if (laterPage) itemsJson(501..501) else itemsJson(1..500)
                                } else itemsJson(1..2)
                                reply(
                                    exchange,
                                    body,
                                    oversized = laterPage && mode == ResponseMode.OVERSIZED_LATER_ITEMS,
                                )
                            }
                            else -> exchange.sendResponseHeaders(400, -1)
                        }
                        else -> exchange.sendResponseHeaders(404, -1)
                    }
                } catch (_: IOException) {
                    // The reader disconnects rejected responses while padding is still being sent.
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        private fun reply(exchange: HttpExchange, prefix: String, oversized: Boolean) {
            val bytes = prefix.toByteArray(Charsets.UTF_8)
            // Oversized replies are chunked, so acceptance must depend on actual bytes read.
            exchange.sendResponseHeaders(200, if (oversized) 0L else bytes.size.toLong())
            exchange.responseBody.use { output ->
                output.write(bytes)
                if (oversized) {
                    val padding = ByteArray(8192) { ' '.code.toByte() }
                    var remaining = 16 * 1024 * 1024 + 1 - bytes.size
                    while (remaining > 0) {
                        val count = minOf(remaining, padding.size)
                        output.write(padding, 0, count)
                        remaining -= count
                    }
                }
            }
        }

        override fun close() { server.stop(0) }
    }

    @Test fun ordinaryGeneratedAlbumRetainsCookieTitleAndThumbnailUrls() {
        Fixture(ResponseMode.ORDINARY).use { fixture ->
            val album = RemoteAlbum.fetch(fixture.shareUrl)
            assertNotNull(album)
            assertEquals("Fixture album", album!!.title)
            assertEquals(mapOf("Cookie" to "sharing_sid=fixture"), album.headers)
            assertEquals(
                listOf(thumbnailUrl(fixture.origin, 1), thumbnailUrl(fixture.origin, 2)),
                album.photoUrls,
            )
            assertEquals(listOf("GET", "GET", "GET"), fixture.requests.map { it.method })
            val metadata = fixture.requests.filter { it.path.endsWith("entry.cgi") }
            assertEquals(listOf("sharing_sid=fixture", "sharing_sid=fixture"), metadata.map { it.cookie })
            assertEquals(
                listOf("\"SYNO.Foto.Browse.Album\"", "\"SYNO.Foto.Browse.Item\""),
                metadata.map { it.params["api"] },
            )
            assertEquals("\"fixture\"", metadata[0].params["passphrase"])
            assertEquals("\"fixture\"", metadata[1].params["_sharing_id"])
            assertEquals("0", metadata[1].params["offset"])
            assertEquals("500", metadata[1].params["limit"])
            assertEquals("\"takentime\"", metadata[1].params["sort_by"])
            assertEquals("\"asc\"", metadata[1].params["sort_direction"])
        }
    }

    @Test fun oversizedLandingRejectsAlbumBeforeMetadataRequests() {
        Fixture(ResponseMode.OVERSIZED_LANDING).use { fixture ->
            assertNull(RemoteAlbum.fetch(fixture.shareUrl))
            assertEquals(listOf("/mo/sharing/fixture"), fixture.requests.map { it.path })
        }
    }

    @Test fun oversizedAlbumMetadataRejectsAlbumBeforeItemRequests() {
        Fixture(ResponseMode.OVERSIZED_ALBUM).use { fixture ->
            assertNull(RemoteAlbum.fetch(fixture.shareUrl))
            assertEquals(2, fixture.requests.size)
            assertEquals("\"SYNO.Foto.Browse.Album\"", fixture.requests[1].params["api"])
        }
    }

    @Test fun oversizedLaterItemsPageRetainsAll500EarlierPhotoUrls() {
        Fixture(ResponseMode.OVERSIZED_LATER_ITEMS).use { fixture ->
            val album = RemoteAlbum.fetch(fixture.shareUrl)
            assertNotNull(album)
            assertEquals("Fixture album", album!!.title)
            assertEquals(mapOf("Cookie" to "sharing_sid=fixture"), album.headers)
            assertEquals((1..500).map { thumbnailUrl(fixture.origin, it) }, album.photoUrls)
            val pages = fixture.requests.filter { it.params["api"] == "\"SYNO.Foto.Browse.Item\"" }
            assertEquals(listOf("0", "500"), pages.map { it.params["offset"] })
            assertEquals(listOf("500", "500"), pages.map { it.params["limit"] })
            assertEquals(listOf("sharing_sid=fixture", "sharing_sid=fixture"), pages.map { it.cookie })
        }
    }

    companion object {
        private fun decodeQuery(query: String): Map<String, String> =
            if (query.isEmpty()) emptyMap() else query.split('&').associate { part ->
                URLDecoder.decode(part.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }

        private fun itemsJson(ids: IntRange): String =
            ids.joinToString(
                separator = ",",
                prefix = """{"success":true,"data":{"list":[""",
                postfix = "]}}",
            ) { id ->
                """{"type":"photo","additional":{"thumbnail":{"unit_id":$id,"cache_key":"cache_$id"}}}"""
            }

        // Independent expected wire URL; never call the production thumbnail builder.
        private fun thumbnailUrl(origin: String, id: Int): String =
            "$origin/mo/sharing/webapi/entry.cgi?id=$id&cache_key=%22cache_$id%22" +
                "&type=%22unit%22&size=%22xl%22&passphrase=%22fixture%22" +
                "&api=%22SYNO.Foto.Thumbnail%22&method=%22get%22&version=2" +
                "&_sharing_id=%22fixture%22"
    }
}
