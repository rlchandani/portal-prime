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
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic

class DavHrefResolutionTest {
    private lateinit var log: MockedStatic<Log>

    @Before fun silenceAndroidLogStub() {
        log = mockStatic(Log::class.java)
    }

    @After fun restoreAndroidLogStub() {
        log.close()
    }

    private class DavFixture(
        listing: (origin: String, path: String) -> String,
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin = "http://127.0.0.1:${server.address.port}"
        val baseUrl = "$origin/photos/"
        val requests = CopyOnWriteArrayList<String>()

        init {
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.rawPath
                requests.add(path)
                try {
                    val bytes = listing(origin, path).toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(207, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (_: IOException) {
                    // The client may disconnect an excluded or rejected response.
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
        }
    }

    // Hrefs are XML text supplied by the fixture, including deliberate entity encodings.
    private fun listing(vararg entries: Pair<String, Boolean>): String =
        entries.joinToString(
            separator = "",
            prefix = """<d:multistatus xmlns:d="DAV:">""",
            postfix = "</d:multistatus>",
        ) { (href, collection) ->
            val resourceType = if (collection) "<d:collection/>" else ""
            "<d:response><d:href>$href</d:href><d:propstat><d:prop>" +
                "<d:resourcetype>$resourceType</d:resourcetype>" +
                "</d:prop></d:propstat></d:response>"
        }

    @Test fun rootRelativePhotoAndSelfCollectionRemainSupported() {
        DavFixture { _, path ->
            if (path == "/photos/") listing(
                "/photos/" to true,
                "/photos/public.jpg" to false,
            ) else listing()
        }.use { server ->
            assertEquals(
                listOf(server.baseUrl + "public.jpg"),
                DavSource.listImageUrls(server.baseUrl, null, null),
            )
            assertEquals(listOf("/photos/"), server.requests.toList())
        }
    }

    @Test fun sameOriginAbsolutePhotoResolvesAndAbsoluteSelfIsNotTraversed() {
        DavFixture { origin, path ->
            if (path == "/photos/") listing(
                "$origin/photos/" to true,
                "$origin/photos/public.jpg" to false,
            ) else listing()
        }.use { server ->
            assertEquals(
                listOf(server.baseUrl + "public.jpg"),
                DavSource.listImageUrls(server.baseUrl, null, null),
            )
            assertEquals(listOf("/photos/"), server.requests.toList())
        }
    }

    @Test fun namedXmlEntitiesAreDecodedExactlyOnce() {
        DavFixture { _, path ->
            if (path == "/photos/") listing(
                "/photos/one&amp;two.jpg" to false,
                "/photos/literal&amp;amp;name.jpg" to false,
            ) else listing()
        }.use { server ->
            assertEquals(
                listOf(
                    server.baseUrl + "one&two.jpg",
                    server.baseUrl + "literal&amp;name.jpg",
                ),
                DavSource.listImageUrls(server.baseUrl, null, null),
            )
        }
    }

    @Test fun decimalAndHexXmlEntitiesResolveToTheSameLiteralCharacter() {
        DavFixture { _, path ->
            if (path == "/photos/") listing(
                "/photos/decimal&#38;name.jpg" to false,
                "/photos/hex&#x26;name.jpg" to false,
            ) else listing()
        }.use { server ->
            assertEquals(
                listOf(
                    server.baseUrl + "decimal&name.jpg",
                    server.baseUrl + "hex&name.jpg",
                ),
                DavSource.listImageUrls(server.baseUrl, null, null),
            )
        }
    }

    @Test fun existingPercentEncodedPathsArePreservedWithoutDoubleEncoding() {
        DavFixture { _, path ->
            if (path == "/photos/") listing(
                "/photos/space%20and%26.jpg" to false,
            ) else listing()
        }.use { server ->
            assertEquals(
                listOf(server.baseUrl + "space%20and%26.jpg"),
                DavSource.listImageUrls(server.baseUrl, null, null),
            )
        }
    }

    @Test fun downloadQueryKeepsThePhotoExtensionAndXmlEscapedParameters() {
        DavFixture { _, path ->
            if (path == "/photos/") listing(
                "/photos/public.jpg?download=1&amp;original=1" to false,
            ) else listing()
        }.use { server ->
            assertEquals(listOf(server.baseUrl + "public.jpg?download=1&original=1"),
                DavSource.listImageUrls(server.baseUrl, null, null))
        }
    }

    @Test fun invalidXmlReferencesDoNotPoisonOtherwiseValidPhotos() {
        DavFixture { _, path ->
            if (path == "/photos/") listing(
                "/photos/public.jpg" to false,
                "/photos/custom&unknown;name.jpg" to false,
                "/photos/zero&#0;name.jpg" to false,
                "/photos/surrogate&#55296;name.jpg" to false,
                "/photos/outside&#1114112;name.jpg" to false,
            ) else listing()
        }.use { server ->
            assertEquals(listOf(server.baseUrl + "public.jpg"),
                DavSource.listImageUrls(server.baseUrl, null, null))
        }
    }

    @Test fun sameOriginAbsoluteNestedCollectionIsFetchedExactlyOnce() {
        DavFixture { origin, path ->
            when (path) {
                "/photos/" -> listing(
                    "$origin/photos/" to true,
                    "$origin/photos/child/" to true,
                )
                "/photos/child/" -> listing(
                    "$origin/photos/child/" to true,
                    "$origin/photos/child/nested.jpg" to false,
                )
                else -> listing()
            }
        }.use { server ->
            assertEquals(
                listOf(server.baseUrl + "child/nested.jpg"),
                DavSource.listImageUrls(server.baseUrl, null, null),
            )
            assertEquals(listOf("/photos/", "/photos/child/"), server.requests.toList())
        }
    }

    @Test fun differentOriginFilesAndCollectionsAreExcludedWithoutForeignRequests() {
        DavFixture { _, _ -> listing("/photos/foreign.jpg" to false) }.use { foreign ->
            DavFixture { _, path ->
                if (path == "/photos/") listing(
                    "/photos/public.jpg" to false,
                    "${foreign.origin}/photos/foreign.jpg" to false,
                    "${foreign.origin}/photos/foreign/" to true,
                ) else listing()
            }.use { server ->
                assertEquals(
                    listOf(server.baseUrl + "public.jpg"),
                    DavSource.listImageUrls(server.baseUrl, null, null),
                )
                assertEquals(listOf("/photos/"), server.requests.toList())
                assertTrue("Foreign origin must receive no requests", foreign.requests.isEmpty())
            }
        }
    }

    @Test fun userInfoFragmentsNonHttpAndMalformedReferencesAreExcluded() {
        DavFixture { origin, path ->
            val authority = origin.removePrefix("http://")
            if (path == "/photos/") listing(
                "/photos/public.jpg" to false,
                "http://public@$authority/photos/user.jpg" to false,
                "http://public@$authority/photos/user/" to true,
                "/photos/fragment.jpg#view" to false,
                "/photos/fragment/#view" to true,
                "ftp://$authority/photos/nonhttp.jpg" to false,
                "ftp://$authority/photos/nonhttp/" to true,
                "http://[invalid/photos/malformed.jpg" to false,
                "http://[invalid/photos/malformed/" to true,
            ) else listing()
        }.use { server ->
            assertEquals(
                listOf(server.baseUrl + "public.jpg"),
                DavSource.listImageUrls(server.baseUrl, null, null),
            )
            assertEquals(listOf("/photos/"), server.requests.toList())
        }
    }

    @Test fun resolvedPhotosRespectTheCapWithoutFetchingQueuedCollections() {
        DavFixture { origin, path ->
            if (path == "/photos/") listing(
                "$origin/photos/child/" to true,
                "$origin/photos/first.jpg" to false,
                "/photos/second.png" to false,
                "/photos/third.jpg" to false,
            ) else listing("/photos/child/nested.jpg" to false)
        }.use { server ->
            assertEquals(
                listOf(server.baseUrl + "first.jpg", server.baseUrl + "second.png"),
                DavSource.listImageUrls(server.baseUrl, null, null, cap = 2),
            )
            assertEquals(listOf("/photos/"), server.requests.toList())
        }
    }

    @Test fun resolvedVideoIsIncludedOnlyWhenRequestedAndSharesTheMediaCap() {
        DavFixture { origin, path ->
            if (path == "/photos/") listing(
                "$origin/photos/first.jpg" to false,
                "$origin/photos/clip.mp4" to false,
                "/photos/second.png" to false,
            ) else listing()
        }.use { server ->
            assertEquals(
                listOf(
                    DavSource.Media(server.baseUrl + "first.jpg", false),
                    DavSource.Media(server.baseUrl + "second.png", false),
                ),
                DavSource.listMedia(server.baseUrl, null, null, includeVideo = false, cap = 2),
            )
            assertEquals(
                listOf(
                    DavSource.Media(server.baseUrl + "first.jpg", false),
                    DavSource.Media(server.baseUrl + "clip.mp4", true),
                ),
                DavSource.listMedia(server.baseUrl, null, null, includeVideo = true, cap = 2),
            )
        }
    }
}
