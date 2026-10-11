/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class WallpaperPreviewsTest {
  // Read the actual bundled JPEG SOF dimensions, rather than pinning a convenient fixture size.
  private fun dimensions(file: File): Pair<Int, Int> = RandomAccessFile(file, "r").use { input ->
    check(input.readUnsignedShort() == 0xffd8)
    while (input.filePointer < input.length()) {
      var marker = input.readUnsignedByte()
      while (marker != 0xff) marker = input.readUnsignedByte()
      do { marker = input.readUnsignedByte() } while (marker == 0xff)
      if (marker == 0xd9 || marker == 0xda) break
      if (marker in 0xd0..0xd8 || marker == 0x01) continue
      val length = input.readUnsignedShort()
      check(length >= 2)
      if (marker in setOf(0xc0, 0xc1, 0xc2)) {
        input.readUnsignedByte()
        val height = input.readUnsignedShort()
        return@use input.readUnsignedShort() to height
      }
      input.seek(input.filePointer + length - 2)
    }
    error("Missing JPEG dimensions: ${file.name}")
  }

  @Test fun bundledPhotoPreviewsFitOneMiBWithoutUpscaling() {
    val directory = listOf(File("src/main/assets/photoframe_fallback"),
        File("app/src/main/assets/photoframe_fallback")).first { it.isDirectory }
    val photos = WallpaperConfig.PHOTOS.map { it.first.removePrefix(WallpaperConfig.PHOTO_PREFIX) }
    var bytes = 0L
    for (photo in photos) {
      val (width, height) = dimensions(File(directory, photo))
      val plan = requireNotNull(SlideshowDecodeBudget.plan(width, height, WallpaperPreviewBudget.MAX_DECODE_EDGE))
      assertTrue("Preview must keep sufficient width: $photo", plan.width >= WallpaperPreviewBudget.WIDTH)
      assertTrue("Preview must keep sufficient height: $photo", plan.height >= WallpaperPreviewBudget.HEIGHT)
      bytes += plan.width.toLong() * plan.height * 4
    }
    assertTrue("Three preview source buffers exceed 1 MiB: $bytes", bytes <= 1024 * 1024)
  }

  @Test fun batchDecodesInOrderOffTheInputThread() = runBlocking {
    val caller = Thread.currentThread()
    var worker: Thread? = null
    val order = mutableListOf<Int>()
    val result = loadWallpaperPreviews(listOf(1, 2, 3), decode = {
      worker = Thread.currentThread(); order += it; "image-$it"
    }, discard = { fail("Published previews must stay drawable") })
    assertNotSame(caller, worker)
    assertEquals(listOf(1, 2, 3), order)
    assertEquals(mapOf(1 to "image-1", 2 to "image-2", 3 to "image-3"), result)
  }

  @Test fun failedAssetDoesNotDiscardOtherUsablePreviews() = runBlocking {
    val result = loadWallpaperPreviews(listOf(1, 2, 3), decode = {
      if (it == 2) throw java.io.IOException()
      "image-$it"
    }, discard = { fail("Published previews must stay drawable") })
    assertEquals(mapOf(1 to "image-1", 3 to "image-3"), result)
  }

  @Test fun cancellationReleasesDecodedImagesAndStopsBeforeTheNextAsset() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val decoded = mutableListOf<Int>()
    val discarded = mutableListOf<String>()
    var published = false
    val job = launch {
      loadWallpaperPreviews(listOf(1, 2, 3), decode = {
        decoded += it
        if (it == 2) { started.complete(Unit); check(release.await(3, TimeUnit.SECONDS)) }
        "image-$it"
      }, discard = { discarded += it })
      published = true
    }
    try { withTimeout(3000) { started.await() }; job.cancel() }
    finally { release.countDown() }
    withTimeout(3000) { job.join() }
    assertFalse(published)
    assertEquals(listOf(1, 2), decoded)
    assertEquals(listOf("image-1", "image-2"), discarded)
  }
}
