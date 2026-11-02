/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.util.Log
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mockStatic

class DavResponseBudgetTest {
    private val photoListing =
        """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/photos/home.jpg</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response></d:multistatus>"""

    private fun withServer(handler: (HttpExchange) -> Unit, check: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/photos/") { exchange ->
            try {
                handler(exchange)
            } catch (_: IOException) {
                // A rejected response may be disconnected while the fixture is still writing.
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            mockStatic(Log::class.java).use {
                check("http://127.0.0.1:${server.address.port}/photos/")
            }
        } finally {
            server.stop(0)
        }
    }

    private fun reply(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun oversizedListing(exchange: HttpExchange, chunked: Boolean) {
        // A valid photo entry followed by legal XML whitespace defeats "empty listing" fallbacks.
        val prefix = photoListing.toByteArray(Charsets.UTF_8)
        val total = 16 * 1024 * 1024 + 1
        exchange.sendResponseHeaders(207, if (chunked) 0L else total.toLong())
        exchange.responseBody.use { output ->
            output.write(prefix)
            val padding = ByteArray(8192) { ' '.code.toByte() }
            var remaining = total - prefix.size
            while (remaining > 0) {
                val count = minOf(remaining, padding.size)
                output.write(padding, 0, count)
                remaining -= count
            }
        }
    }

    @Test fun oversizedDeclaredRootListingReturnsFailure() =
        withServer({ oversizedListing(it, chunked = false) }) { url ->
            assertNull(DavSource.listImageUrls(url, null, null))
        }

    @Test fun oversizedChunkedRootListingReturnsFailure() =
        withServer({ oversizedListing(it, chunked = true) }) { url ->
            assertNull(DavSource.listImageUrls(url, null, null))
        }

    @Test fun ordinaryMultistatusListingRetainsPhotosAndRequestHeaders() {
        val requestHeaders = AtomicReference<Pair<String?, String?>>()
        withServer({ exchange ->
            requestHeaders.set(
                exchange.requestHeaders.getFirst("Depth") to
                    exchange.requestHeaders.getFirst("User-Agent"),
            )
            reply(exchange, 207, photoListing)
        }) { url ->
            assertEquals(listOf(url + "home.jpg"), DavSource.listImageUrls(url, null, null))
            assertEquals("1" to "PortalPhotoFrame/1.0", requestHeaders.get())
        }
    }

    @Test fun ordinary200RemainsAReachableFolder() {
        val depth = AtomicReference<String?>()
        withServer({ exchange ->
            depth.set(exchange.requestHeaders.getFirst("Depth"))
            reply(exchange, 200, photoListing)
        }) { url ->
            assertTrue(DavSource.testConnection(url, null, null))
            assertEquals("0", depth.get())
        }
    }

    @Test fun unreadableChildKeepsPhotosAlreadyCollectedFromRoot() {
        val rootListing = photoListing.replace(
            "</d:multistatus>",
            "<d:response><d:href>/photos/child/</d:href><d:propstat><d:prop>" +
                "<d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>" +
                "</d:response></d:multistatus>",
        )
        withServer({ exchange ->
            if (exchange.requestURI.path == "/photos/child/")
                reply(exchange, 403, "Denied")
            else reply(exchange, 207, rootListing)
        }) { url ->
            assertEquals(listOf(url + "home.jpg"), DavSource.listImageUrls(url, null, null))
        }
    }
}
