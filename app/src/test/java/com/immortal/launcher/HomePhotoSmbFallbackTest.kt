/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Real Home loader; generated SMB streams and Android decoding substitutes only. */
class HomePhotoSmbFallbackTest {
    @Test fun corruptBoundsFirstFallsThroughToValidSecond() {
        withPhotos(listOf("bad", "good")) { actual, good, attempts, _ ->
            assertSame(good, actual)
            assertEquals(listOf("bad:bounds", "good:bounds", "good:full"), attempts)
        }
    }

    @Test fun nullFullDecodeFirstFallsThroughToValidSecond() {
        withPhotos(listOf("full-null", "good")) { actual, good, attempts, _ ->
            assertSame(good, actual)
            assertEquals(listOf("full-null:bounds", "full-null:full",
                "good:bounds", "good:full"), attempts)
        }
    }

    @Test fun ordinaryDecoderFailureFirstFallsThroughToValidSecond() {
        withPhotos(listOf("decode-throws", "good")) { actual, good, attempts, _ ->
            assertSame(good, actual)
            assertEquals(listOf("decode-throws:bounds", "good:bounds", "good:full"), attempts)
        }
    }

    @Test fun missingFirstStreamFallsThroughToValidSecond() {
        withPhotos(listOf("missing", "good")) { actual, good, attempts, opened ->
            assertSame(good, actual)
            assertEquals(listOf("good:bounds", "good:full"), attempts)
            assertEquals(listOf("missing", "good", "good", "good"), opened)
        }
    }

    @Test fun ordinaryOpenFailureFirstFallsThroughToValidSecond() {
        withPhotos(listOf("open-throws", "good")) { actual, good, attempts, opened ->
            assertSame(good, actual)
            assertEquals(listOf("good:bounds", "good:full"), attempts)
            assertEquals(listOf("open-throws", "good", "good", "good"), opened)
        }
    }

    @Test fun validFirstStopsBeforeOpeningLaterImage() {
        withPhotos(listOf("good", "decode-throws")) { actual, good, attempts, opened ->
            assertSame(good, actual)
            assertEquals(listOf("good:bounds", "good:full"), attempts)
            assertEquals(listOf("good", "good", "good"), opened)
        }
    }

    @Test fun allBadImagesReturnNullAndCloseTheSource() {
        withPhotos(listOf("bad", "bad")) { actual, _, attempts, _ ->
            assertNull(actual)
            assertTrue(attempts.all { it == "bad:bounds" })
        }
    }

    @Test fun emptyListingReturnsNullWithoutOpeningAStream() {
        withPhotos(emptyList()) { actual, _, attempts, opened ->
            assertNull(actual)
            assertTrue(attempts.isEmpty())
            assertTrue(opened.isEmpty())
        }
    }

    @Test fun failedConnectionReturnsNullWithoutListingOrOpening() {
        withPhotos(listOf("good"), connected = false) { actual, _, attempts, opened ->
            assertNull(actual)
            assertTrue(attempts.isEmpty())
            assertTrue(opened.isEmpty())
        }
    }

    @Test fun imageBeyondTheExistingSixtyFileCapIsNotOpened() {
        withPhotos(List(60) { "bad" } + "good") { actual, _, attempts, opened ->
            assertNull(actual)
            assertFalse(attempts.any { it.startsWith("good:") })
            assertFalse(opened.contains("good"))
        }
    }

    private fun withPhotos(
        markers: List<String>,
        connected: Boolean = true,
        check: (Bitmap?, Bitmap, List<String>, List<String>) -> Unit,
    ) {
        val settings = ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_SMB,
            smbHost = "generated-host.invalid", smbShare = "photos", smbPath = "",
            smbUser = "", smbPass = "")
        val context = Mockito.mock(Context::class.java)
        val good = Mockito.mock(Bitmap::class.java)
        val attempts = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val paths = markers.indices.map { "generated-$it.jpg" }
        var createdStreams = 0
        var closedStreams = 0
        Mockito.mockStatic(android.util.Log::class.java).use {
            Mockito.mockConstruction(SmbSource::class.java) { smb, _ ->
                Mockito.`when`(smb.connect()).thenReturn(connected)
                Mockito.`when`(smb.listImages(60)).thenReturn(paths.take(60))
                Mockito.`when`(smb.openStream(Mockito.anyString())).thenAnswer { call ->
                    val path = call.getArgument<String>(0)
                    val marker = markers[paths.indexOf(path)]
                    opened.add(marker)
                    when (marker) {
                        "missing" -> null
                        "open-throws" -> throw IOException("Generated open failure")
                        else -> {
                            createdStreams++
                            object : ByteArrayInputStream(marker.toByteArray()) {
                                override fun close() {
                                    closedStreams++
                                    super.close()
                                }
                            }
                        }
                    }
                }
            }.use { sources ->
                Mockito.mockConstruction(BitmapFactory.Options::class.java).use {
                    Mockito.mockConstruction(Matrix::class.java).use {
                        Mockito.mockConstruction(ExifInterface::class.java) { exif, _ ->
                            Mockito.`when`(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL)).thenReturn(ExifInterface.ORIENTATION_NORMAL)
                        }.use {
                            Mockito.mockStatic(BitmapFactory::class.java).use { decoding ->
                                decoding.`when`<Bitmap> {
                                    BitmapFactory.decodeStream(Mockito.any(InputStream::class.java),
                                        Mockito.isNull<Rect>(), Mockito.any(BitmapFactory.Options::class.java))
                                }.thenAnswer { call ->
                                    val marker = call.getArgument<InputStream>(0).bufferedReader().readText()
                                    val options = call.getArgument<BitmapFactory.Options>(2)
                                    attempts.add(marker + if (options.inJustDecodeBounds) ":bounds" else ":full")
                                    if (marker == "decode-throws") throw IOException("Generated decode failure")
                                    options.outWidth = if (marker == "bad") -1 else 320
                                    options.outHeight = if (marker == "bad") -1 else 240
                                    when {
                                        options.inJustDecodeBounds -> null
                                        marker == "full-null" -> null
                                        marker == "good" -> good
                                        else -> throw AssertionError("Unexpected generated marker")
                                    }
                                }
                                val loader = Class.forName("com.immortal.launcher.ApprovedHomePhotoKt")
                                    .getDeclaredMethod("loadApprovedHomePhoto", Context::class.java,
                                        ScreensaverConfig.Settings::class.java).apply { isAccessible = true }
                                val actual = loader.invoke(null, context, settings) as Bitmap?
                                assertEquals(1, sources.constructed().size)
                                val source = sources.constructed().single()
                                Mockito.verify(source).close()
                                if (connected) Mockito.verify(source).listImages(60)
                                else Mockito.verify(source, Mockito.never()).listImages(Mockito.anyInt())
                                assertEquals(createdStreams, closedStreams)
                                check(actual, good, attempts.toList(), opened.toList())
                            }
                        }
                    }
                }
            }
        }
    }
}
