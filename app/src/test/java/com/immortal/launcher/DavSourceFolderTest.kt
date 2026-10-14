/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DavSourceFolderTest {
    private fun withFolder(code: Int, body: String, check: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/photos/") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { check("http://127.0.0.1:${server.address.port}/photos/") }
        finally { server.stop(0) }
    }
    private val emptyFolder = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/photos/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response></d:multistatus>"""
    @Test fun reachableFolderWithNoPhotosReturnsEmptySuccess() = withFolder(207, emptyFolder) { url ->
        assertEquals(emptyList<String>(), DavSource.listImageUrls(url, null, null))
    }
    @Test fun unreadableRootReturnsFailureInsteadOfEmptySuccess() = withFolder(403, "Denied") { url ->
        assertNull(DavSource.listImageUrls(url, null, null))
    }
    @Test fun actualPhotoListingRetainsItsResolvedUrl() {
        val body = emptyFolder.replace("</d:multistatus>", "<d:response><d:href>/photos/home.jpg</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response></d:multistatus>")
        withFolder(207, body) { url ->
            assertEquals(listOf(url + "home.jpg"), DavSource.listImageUrls(url, null, null))
        }
    }
}
