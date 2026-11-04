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
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/** Actual Home loader and local enumeration; only Android decoding is substituted. */
class HomePhotoLocalFallbackTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun corruptBoundsFirstFallsThroughToValidSecond() {
    withPhotos(listOf("01_bad.jpg" to "bad", "02_good.png" to "good")) { actual, good, attempts, _ ->
      assertSame(good, actual)
      assertEquals(listOf("bad:bounds", "good:bounds", "good:full"), attempts)
    }
  }

  @Test fun nullFullDecodeFirstFallsThroughToValidSecond() {
    withPhotos(listOf("01_bad.jpg" to "full-null", "02_good.png" to "good")) { actual, good, attempts, _ ->
      assertSame(good, actual)
      assertEquals(listOf("full-null:bounds", "full-null:full", "good:bounds", "good:full"), attempts)
    }
  }

  @Test fun throwingDecoderFirstFallsThroughToValidSecond() {
    withPhotos(listOf("01_bad.jpg" to "throws", "02_good.png" to "good")) { actual, good, attempts, _ ->
      assertSame(good, actual)
      assertEquals(listOf("throws:bounds", "good:bounds", "good:full"), attempts)
    }
  }

  @Test fun validFirstStopsBeforeReadingLaterImage() {
    withPhotos(listOf("01_good.png" to "good", "02_bad.jpg" to "throws")) { actual, good, attempts, _ ->
      assertSame(good, actual)
      assertEquals(listOf("good:bounds", "good:full"), attempts)
    }
  }

  @Test fun allBadFolderReturnsNull() {
    withPhotos(listOf("01_bad.jpg" to "bad")) { actual, _, attempts, _ ->
      assertNull(actual)
      assertEquals(listOf("bad:bounds"), attempts)
    }
  }

  @Test fun emptyFolderReturnsNullWithoutDecode() {
    withPhotos(emptyList()) { actual, _, attempts, _ ->
      assertNull(actual)
      assertEquals(emptyList<String>(), attempts)
    }
  }

  @Test fun nonImagesAndVideoAreSkippedBeforeValidImage() {
    withPhotos(listOf("01_note.txt" to "throws", "02_clip.mp4" to "throws", "03_good.png" to "good")) {
        actual, good, attempts, _ ->
      assertSame(good, actual)
      assertEquals(listOf("good:bounds", "good:full"), attempts)
    }
  }

  @Test fun validImageBeyondExistingSixtyItemLimitIsNotDecoded() {
    val files = (1..60).map { index -> index.toString().padStart(2, '0') + "_bad.jpg" to "bad" } +
        listOf("61_good.png" to "good")
    withPhotos(files) { actual, _, attempts, folder ->
      val admitted = LocalMedia.enumerate(folder.path, includeVideo = false, max = 60)
      assertEquals(60, admitted.size)
      assertEquals("01_bad.jpg", File(admitted.first().path).name)
      assertEquals("60_bad.jpg", File(admitted.last().path).name)
      assertNull(actual)
      assertFalse(attempts.any { it.startsWith("good:") })
    }
  }

  private fun withPhotos(
      files: List<Pair<String, String>>,
      check: (Bitmap?, Bitmap, List<String>, File) -> Unit,
  ) {
    val folder = temporary.newFolder()
    files.forEach { (name, marker) -> File(folder, name).writeText(marker) }
    val settings = ScreensaverConfig.Settings(
        source = ScreensaverConfig.SOURCE_FOLDER, folderPath = folder.path)
    val context = Mockito.mock(Context::class.java)
    val good = Mockito.mock(Bitmap::class.java)
    val attempts = mutableListOf<String>()
    // Options and Matrix constructors are Android stubs on the host. No transform is selected.
    Mockito.mockStatic(android.util.Log::class.java).use {
      Mockito.mockConstruction(BitmapFactory.Options::class.java).use {
        Mockito.mockConstruction(Matrix::class.java).use {
          Mockito.mockConstruction(ExifInterface::class.java) { exif, _ ->
            Mockito.`when`(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
                .thenReturn(ExifInterface.ORIENTATION_NORMAL)
          }.use {
            Mockito.mockStatic(BitmapFactory::class.java).use { decoding ->
              decoding.`when`<Bitmap> {
                BitmapFactory.decodeStream(Mockito.any(InputStream::class.java),
                    Mockito.isNull<Rect>(), Mockito.any(BitmapFactory.Options::class.java))
              }.thenAnswer { call ->
                val stream = call.getArgument<InputStream>(0)
                val options = call.getArgument<BitmapFactory.Options>(2)
                val marker = stream.bufferedReader().readText()
                val pass = if (options.inJustDecodeBounds) "bounds" else "full"
                attempts.add(marker + ":" + pass)
                if (marker == "throws") throw IOException("Generated decoder failure")
                options.outWidth = if (marker == "bad") -1 else 320
                options.outHeight = if (marker == "bad") -1 else 240
                when {
                  options.inJustDecodeBounds -> null
                  marker == "full-null" -> null
                  marker == "good" -> good
                  else -> throw AssertionError("Unexpected generated photo marker: " + marker)
                }
              }
              val loader = Class.forName("com.immortal.launcher.ApprovedHomePhotoKt")
                  .getDeclaredMethod("loadApprovedHomePhoto", Context::class.java,
                      ScreensaverConfig.Settings::class.java).apply { isAccessible = true }
              val actual = loader.invoke(null, context, settings) as Bitmap?
              check(actual, good, attempts.toList(), folder)
            }
          }
        }
      }
    }
  }
}
