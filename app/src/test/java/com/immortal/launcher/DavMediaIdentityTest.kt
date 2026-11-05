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
import org.junit.Test
import org.mockito.Mockito.mockStatic

/** Real generated PROPFIND responses exercised through both public listing methods. */
class DavMediaIdentityTest {
    private fun listing(vararg entries: Pair<String, Boolean>): String =
        entries.joinToString("", """<d:multistatus xmlns:d="DAV:">""", "</d:multistatus>") {
            (href, collection) ->
            "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>" +
                (if (collection) "<d:collection/>" else "") +
                "</d:resourcetype></d:prop></d:propstat></d:response>"
        }

    private fun withServer(
        response: (String, String) -> String,
        check: (String, List<String>) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin = "http://127.0.0.1:${server.address.port}"
        val requests = CopyOnWriteArrayList<String>()
        server.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.rawPath
                requests.add(path)
                val body = response(origin, path).toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(207, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            mockStatic(Log::class.java).use { check(origin, requests) }
        } finally {
            server.stop(0)
        }
    }

    @Test fun repeatedRootImageDoesNotConsumeTheDistinctPhotoCap() {
        withServer({ _, _ -> listing("/photos/a.jpg" to false, "/photos/a.jpg" to false,
            "/photos/b.png" to false) }) { origin, requests ->
            assertEquals(listOf("$origin/photos/a.jpg", "$origin/photos/b.png"),
                DavSource.listImageUrls("$origin/photos/", null, null, cap = 2))
            assertEquals(listOf("/photos/"), requests)
        }
    }

    @Test fun absoluteAndPathHrefsShareTheSameResolvedMediaIdentity() {
        withServer({ origin, _ -> listing("/photos/a.jpg" to false,
            "$origin/photos/a.jpg" to false, "/photos/b.png" to false) }) { origin, requests ->
            assertEquals(listOf("$origin/photos/a.jpg", "$origin/photos/b.png"),
                DavSource.listImageUrls("$origin/photos/", null, null, cap = 2))
            assertEquals(listOf("/photos/"), requests)
        }
    }

    @Test fun repeatedMediaAcrossCollectionsDoesNotHideALaterPhoto() {
        withServer({ _, path ->
            when (path) {
                "/photos/" -> listing("/photos/a.jpg" to false, "/photos/child/" to true)
                "/photos/child/" -> listing("/photos/a.jpg" to false, "/photos/child/b.jpg" to false)
                else -> throw AssertionError("Unexpected generated collection")
            }
        }) { origin, requests ->
            assertEquals(listOf("$origin/photos/a.jpg", "$origin/photos/child/b.jpg"),
                DavSource.listImageUrls("$origin/photos/", null, null, cap = 2))
            assertEquals(listOf("/photos/", "/photos/child/"), requests)
        }
    }

    @Test fun repeatedVideoUsesOnlyOneMediaSlotWhenIncluded() {
        withServer({ _, _ -> listing("/photos/a.mp4" to false, "/photos/a.mp4" to false,
            "/photos/b.jpg" to false) }) { origin, requests ->
            assertEquals(listOf(DavSource.Media("$origin/photos/a.mp4", true),
                DavSource.Media("$origin/photos/b.jpg", false)),
                DavSource.listMedia("$origin/photos/", null, null, includeVideo = true, cap = 2))
            assertEquals(listOf("/photos/"), requests)
        }
    }

    @Test fun uniqueImagesKeepTheirOriginalOrderAndLimit() {
        withServer({ _, _ -> listing("/photos/z.jpg" to false, "/photos/a.png" to false,
            "/photos/third.jpg" to false) }) { origin, requests ->
            assertEquals(listOf("$origin/photos/z.jpg", "$origin/photos/a.png"),
                DavSource.listImageUrls("$origin/photos/", null, null, cap = 2))
            assertEquals(listOf("/photos/"), requests)
        }
    }

    @Test fun distinctDirectoriesKeepPhotosWithTheSameBasename() {
        withServer({ _, _ -> listing("/photos/one/a.jpg" to false,
            "/photos/two/a.jpg" to false) }) { origin, requests ->
            assertEquals(listOf("$origin/photos/one/a.jpg", "$origin/photos/two/a.jpg"),
                DavSource.listImageUrls("$origin/photos/", null, null, cap = 2))
            assertEquals(listOf("/photos/"), requests)
        }
    }

    @Test fun excludedVideosDoNotConsumeImageSlots() {
        withServer({ _, _ -> listing("/photos/a.mp4" to false, "/photos/a.mp4" to false,
            "/photos/b.jpg" to false, "/photos/c.png" to false) }) { origin, requests ->
            assertEquals(listOf(DavSource.Media("$origin/photos/b.jpg", false),
                DavSource.Media("$origin/photos/c.png", false)),
                DavSource.listMedia("$origin/photos/", null, null, includeVideo = false, cap = 2))
            assertEquals(listOf("/photos/"), requests)
        }
    }

    @Test fun emptyReadableCollectionRemainsAnEmptySuccess() {
        withServer({ _, _ -> listing("/photos/" to true) }) { origin, requests ->
            assertEquals(emptyList<String>(), DavSource.listImageUrls("$origin/photos/", null, null))
            assertEquals(listOf("/photos/"), requests)
        }
    }
}
