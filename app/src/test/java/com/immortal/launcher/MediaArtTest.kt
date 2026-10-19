/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class MediaArtTest {
  @Test fun httpAndHttpsResolveThroughTheNetworkLoaderAt384Pixels() {
    val cover = Mockito.mock(Bitmap::class.java)
    for (url in listOf("http://fixture/cover", "https://fixture/cover", "HTTPS://fixture/cover")) {
      val requests = mutableListOf<Pair<String, Int>>()
      var localReads = 0
      val actual = MediaArt.resolveUri(url,
          network = { requested, edge -> requests.add(requested to edge); cover },
          local = { _, _ -> localReads++; null })
      assertSame(cover, actual)
      assertEquals(listOf(url to 384), requests)
      assertEquals(0, localReads)
    }
  }

  @Test fun contentFileAndResourceUrisResolveThroughTheLocalLoaderAt384Pixels() {
    val cover = Mockito.mock(Bitmap::class.java)
    for (url in listOf("content://fixture/cover", "file:///fixture/cover.png",
        "android.resource://fixture/123", "CONTENT://fixture/cover")) {
      val requests = mutableListOf<Pair<String, Int>>()
      var networkReads = 0
      val actual = MediaArt.resolveUri(url,
          network = { _, _ -> networkReads++; null },
          local = { requested, edge -> requests.add(requested to edge); cover })
      assertSame(cover, actual)
      assertEquals(listOf(url to 384), requests)
      assertEquals(0, networkReads)
    }
  }

  @Test fun unsupportedMalformedAndBlankUrisDoNotInvokeEitherLoaderOrTheContext() {
    val context = Mockito.mock(Context::class.java)
    var reads = 0
    for (url in listOf("", " \t", "/fixture/cover", "ftp://fixture/cover",
        "data:image/png;base64,AAAA", "content://fixture/%zz", "https://fixture/bad\npath")) {
      assertNull(MediaArt.resolveUri(url,
          network = { _, _ -> reads++; null },
          local = { _, _ -> reads++; null }))
      assertNull(MediaArt.resolveUri(context, url))
    }
    assertEquals(0, reads)
    Mockito.verifyNoInteractions(context)
  }

  @Test fun failedOrMissingArtworkNeverFallsThroughToTheOtherTransport() {
    for ((url, expected) in listOf("https://fixture/cover" to "network",
        "content://fixture/cover" to "local")) {
      val calls = mutableListOf<String>()
      assertNull(MediaArt.resolveUri(url,
          network = { _, _ -> calls.add("network"); throw IOException("synthetic failed read") },
          local = { _, _ -> calls.add("local"); throw SecurityException("synthetic denied grant") }))
      assertEquals(listOf(expected), calls)
      calls.clear()
      assertNull(MediaArt.resolveUri(url,
          network = { _, _ -> calls.add("network"); null },
          local = { _, _ -> calls.add("local"); null }))
      assertEquals(listOf(expected), calls)
    }
  }

  @Test fun sharedBitmapsAreReturnedUnchangedWithoutAnotherCacheOrRecycling() {
    val first = Mockito.mock(Bitmap::class.java)
    val next = Mockito.mock(Bitmap::class.java)
    var reads = 0
    val network: (String, Int) -> Bitmap? = { _, _ -> if (reads++ == 0) first else next }
    assertSame(first, MediaArt.resolveUri("https://fixture/cover", network) { _, _ -> null })
    assertSame(next, MediaArt.resolveUri("https://fixture/cover", network) { _, _ -> null })
    assertEquals(2, reads)
    Mockito.verifyNoInteractions(first, next)
  }

  @Test fun publicLocalResolutionUsesSampled384PixelDecodeAndClosesTheProvider() {
    val url = "content://fixture/cover"
    var closed = false
    val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
      override fun close() { closed = true; super.close() }
    }
    val cover = Mockito.mock(Bitmap::class.java)
    Mockito.`when`(cover.width).thenReturn(250)
    Mockito.`when`(cover.height).thenReturn(188)
    Mockito.`when`(cover.allocationByteCount).thenReturn(250 * 188 * 4)
    val samples = mutableListOf<Int>()
    withProvider(url, input) { context ->
      Mockito.mockConstruction(BitmapFactory.Options::class.java).use {
        Mockito.mockStatic(BitmapFactory::class.java).use { decoder ->
          decoder.`when`<Bitmap> {
            BitmapFactory.decodeByteArray(Mockito.any(ByteArray::class.java), Mockito.anyInt(),
                Mockito.anyInt(), Mockito.any(BitmapFactory.Options::class.java))
          }.thenAnswer { call ->
            val options = call.getArgument<BitmapFactory.Options>(3)
            if (options.inJustDecodeBounds) {
              options.outWidth = 4000
              options.outHeight = 3000
              null
            } else {
              samples.add(options.inSampleSize)
              assertFalse(options.inScaled)
              assertEquals(Bitmap.Config.ARGB_8888, options.inPreferredConfig)
              cover
            }
          }
          assertSame(cover, MediaArt.resolveUri(context, url))
        }
      }
    }
    assertEquals(listOf(16), samples)
    assertTrue(closed)
    Mockito.verify(cover, Mockito.never()).recycle()
  }

  @Test fun publicLocalResolutionHandlesResolverPermissionDenial() {
    val context = Mockito.mock(Context::class.java)
    Mockito.`when`(context.contentResolver).thenThrow(SecurityException("synthetic denied resolver"))
    assertNull(MediaArt.resolveUri(context, "content://fixture/private"))
  }

  @Test fun oversizedLocalArtworkStopsAtEightMiBPlusOneProbeAndCloses() {
    val url = "content://fixture/cover"
    val input = GeneratedInput(8 * 1024 * 1024 + 2)
    withProvider(url, input) { context ->
      assertNull(MediaArt.resolveUri(context, url))
      assertEquals(8 * 1024 * 1024 + 1, input.bytesRead)
      assertTrue(input.closed)
    }
  }

  private fun withProvider(url: String, input: InputStream, block: (Context) -> Unit) {
    val context = Mockito.mock(Context::class.java)
    val resolver = Mockito.mock(ContentResolver::class.java)
    val uri = Mockito.mock(Uri::class.java)
    Mockito.`when`(context.contentResolver).thenReturn(resolver)
    Mockito.`when`(uri.normalizeScheme()).thenReturn(uri)
    Mockito.`when`(resolver.openInputStream(uri)).thenReturn(input)
    Mockito.mockStatic(Uri::class.java).use { uris ->
      uris.`when`<Uri> { Uri.parse(url) }.thenReturn(uri)
      block(context)
    }
  }

  /** A finite oversized provider response without allocating a matching fixture. */
  private class GeneratedInput(private var remaining: Int) : InputStream() {
    var bytesRead = 0
      private set
    var closed = false
      private set
    override fun read(): Int {
      if (remaining == 0) return -1
      remaining--
      bytesRead++
      return 0x5a
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
      if (length == 0) return 0
      if (remaining == 0) return -1
      val count = minOf(length, remaining)
      bytes.fill(0x5a.toByte(), offset, offset + count)
      remaining -= count
      bytesRead += count
      return count
    }
    override fun close() { closed = true }
  }
}
