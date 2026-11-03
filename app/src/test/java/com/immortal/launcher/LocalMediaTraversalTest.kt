/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real folder trees and symlinks exercise traversal rather than mocked file listings. */
class LocalMediaTraversalTest {
  private lateinit var root: Path

  @Before
  fun createPublicTree() {
    root = Files.createTempDirectory("immortal-public-media-traversal-").toRealPath()
  }

  @After
  fun removeTreeWithoutFollowingSymlinks() {
    if (!::root.isInitialized) return
    // Files.walk does not follow symbolic links unless FOLLOW_LINKS is requested.
    Files.walk(root).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
  }

  private fun media(relative: String): Path {
    val path = root.resolve(relative)
    Files.createDirectories(path.parent)
    Files.write(path, byteArrayOf(1, 2, 3))
    return path
  }

  private fun canonicalPaths(items: List<MediaItem>): Set<String> =
      items.map { File(it.path).canonicalPath }.toSet()

  private fun aliasTree(): Path {
    val photo = media("album/public.jpg")
    Files.createSymbolicLink(root.resolve("alias-a"), photo.parent)
    Files.createSymbolicLink(root.resolve("alias-b"), photo.parent)
    return photo
  }

  @Test
  fun enumerateCountsPhysicalDirectoryOnceAcrossAliases() {
    val photo = aliasTree()
    val items = LocalMedia.enumerate(root.toString(), includeVideo = true, max = 20)
    assertEquals(1, items.size)
    assertEquals(setOf(photo.toFile().canonicalPath), canonicalPaths(items))
  }

  @Test
  fun summarizeCountsPhysicalDirectoryOnceAcrossAliases() {
    val photo = aliasTree()
    val summary = LocalMedia.summarize(root.toString(), maxCount = 20, maxScan = 100, sampleCount = 10)
    assertEquals(1, summary.photos)
    assertEquals(0, summary.videos)
    assertFalse(summary.capped)
    assertEquals(1, summary.samples.size)
    assertEquals(setOf(photo.toFile().canonicalPath), canonicalPaths(summary.samples))
  }

  @Test
  fun enumerateCycleWithPhotoDoesNotRepeatThatPhoto() {
    val photo = media("public.jpg")
    Files.createSymbolicLink(root.resolve("loop"), root)
    // The existing item cap also bounds the buggy baseline; no background timeout worker is needed.
    val items = LocalMedia.enumerate(root.toString(), includeVideo = false, max = 4)
    assertEquals(1, items.size)
    assertEquals(setOf(photo.toFile().canonicalPath), canonicalPaths(items))
  }

  @Test
  fun summarizeCycleWithPhotoDoesNotInflateCountsOrSamples() {
    val photo = media("public.jpg")
    Files.createSymbolicLink(root.resolve("loop"), root)
    val summary = LocalMedia.summarize(root.toString(), maxCount = 4, maxScan = 100, sampleCount = 10)
    assertEquals(1, summary.photos)
    assertEquals(0, summary.videos)
    assertFalse(summary.capped)
    assertEquals(1, summary.samples.size)
    assertEquals(setOf(photo.toFile().canonicalPath), canonicalPaths(summary.samples))
  }

  @Test
  fun directoryOnlyTreeConsumesScanBudgetAndReportsCapped() {
    repeat(8) { Files.createDirectories(root.resolve("branch-$it/child/grandchild")) }
    val summary = LocalMedia.summarize(root.toString(), maxCount = 20, maxScan = 3)
    assertEquals(0, summary.total)
    assertTrue(summary.samples.isEmpty())
    assertTrue("Unvisited directories remain after the scan budget", summary.capped)
  }

  @Test
  fun nestedJunkDirectoriesRemainExcluded() {
    val photo = media("keep.jpg")
    for (name in listOf(".hidden", "aNdRoId", "CACHE", "sCrEeNsHoTs")) {
      media("$name/ignored.jpg")
    }
    val items = LocalMedia.enumerate(root.toString(), includeVideo = true)
    assertEquals(1, items.size)
    assertEquals(setOf(photo.toFile().canonicalPath), canonicalPaths(items))
    val summary = LocalMedia.summarize(root.toString())
    assertEquals(1, summary.photos)
    assertEquals(0, summary.videos)
    assertFalse(summary.capped)
  }

  @Test
  fun explicitlyChosenScreenshotsRootStillReadsOrdinaryDescendants() {
    val first = media("Screenshots/chosen.png")
    val second = media("Screenshots/Album/nested.jpg")
    val video = media("Screenshots/Album/public.mp4")
    val selected = root.resolve("Screenshots")
    val items = LocalMedia.enumerate(selected.toString(), includeVideo = true)
    assertEquals(3, items.size)
    assertEquals(
        setOf(first, second, video).map { it.toFile().canonicalPath }.toSet(),
        canonicalPaths(items))
    val summary = LocalMedia.summarize(selected.toString())
    assertEquals(2, summary.photos)
    assertEquals(1, summary.videos)
    assertFalse(summary.capped)
    assertTrue(LocalMedia.enumerate(root.toString(), includeVideo = true).isEmpty())
    assertEquals(0, LocalMedia.summarize(root.toString()).total)
  }

  @Test
  fun ordinaryNestedPhotosAndOptionalVideosRemainSupported() {
    val cover = media("cover.JPG")
    val nested = media("Trip/nested/beach.jpeg")
    val video = media("Trip/clip.MP4")
    media("Trip/notes.txt")
    val images = LocalMedia.enumerate(root.toString(), includeVideo = false)
    assertEquals(2, images.size)
    assertTrue(images.none { it.isVideo })
    assertEquals(
        setOf(cover, nested).map { it.toFile().canonicalPath }.toSet(), canonicalPaths(images))
    val all = LocalMedia.enumerate(root.toString(), includeVideo = true)
    assertEquals(3, all.size)
    assertEquals(1, all.count { it.isVideo })
    assertEquals(
        setOf(cover, nested, video).map { it.toFile().canonicalPath }.toSet(), canonicalPaths(all))
    val summary = LocalMedia.summarize(root.toString(), sampleCount = 10)
    assertEquals(2, summary.photos)
    assertEquals(1, summary.videos)
    assertEquals(3, summary.samples.size)
    assertEquals(canonicalPaths(all), canonicalPaths(summary.samples))
    assertFalse(summary.capped)
  }

  @Test
  fun nonpositiveEnumerationItemLimitsReturnNoItems() {
    media("public.jpg")
    for (max in listOf(0, -1)) {
      assertTrue(LocalMedia.enumerate(root.toString(), includeVideo = true, max = max).isEmpty())
    }
  }

  @Test
  fun nonpositiveSampleCountsStillCountMediaWithoutSamples() {
    media("public.jpg")
    media("public.mp4")
    for (sampleCount in listOf(0, -1)) {
      val summary = LocalMedia.summarize(root.toString(), sampleCount = sampleCount)
      assertEquals(1, summary.photos)
      assertEquals(1, summary.videos)
      assertTrue(summary.samples.isEmpty())
      assertFalse(summary.capped)
    }
  }

  @Test
  fun enumerationDirectoryEntriesExhaustBudgetBeforeLaterPhoto() {
    repeat(3) { Files.createDirectories(root.resolve("branch-$it/child")) }
    media("z-public.jpg")
    val items = LocalMedia.enumerate(root.toString(), includeVideo = true, max = 20, maxScan = 2)
    assertTrue("Directory entries must consume the scan budget", items.isEmpty())
  }

  @Test
  fun enumerationNonmediaEntriesConsumeBudgetBeforePhoto() {
    media("a.txt")
    media("b.json")
    media("c.dat")
    val photo = media("z-public.jpg")
    assertTrue(
        LocalMedia.enumerate(root.toString(), includeVideo = true, max = 20, maxScan = 3).isEmpty())
    val items = LocalMedia.enumerate(root.toString(), includeVideo = true, max = 20, maxScan = 4)
    assertEquals(1, items.size)
    assertEquals(setOf(photo.toFile().canonicalPath), canonicalPaths(items))
  }

  @Test
  fun enumerationExactEntryBudgetStillIncludesOrdinaryPhotoAndVideo() {
    val photo = media("public.jpg")
    val video = media("public.mp4")
    val items = LocalMedia.enumerate(root.toString(), includeVideo = true, max = 20, maxScan = 2)
    assertEquals(2, items.size)
    assertEquals(1, items.count { it.isVideo })
    assertEquals(
        setOf(photo, video).map { it.toFile().canonicalPath }.toSet(), canonicalPaths(items))
  }

  @Test
  fun nonpositiveSummaryCountOrScanBudgetReturnsEmptyCappedResult() {
    media("public.jpg")
    media("public.mp4")
    for ((maxCount, maxScan) in listOf(0 to 10, -1 to 10, 10 to 0, 10 to -1)) {
      val summary = LocalMedia.summarize(root.toString(), maxCount = maxCount, maxScan = maxScan)
      assertEquals(0, summary.photos)
      assertEquals(0, summary.videos)
      assertTrue(summary.samples.isEmpty())
      assertTrue("Nonpositive budgets cannot confirm a complete scan", summary.capped)
    }
  }
}
