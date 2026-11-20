/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folder scanning limits and edge inputs, on a throwaway temp tree. */
class LocalMediaCoverageTest {
  private fun <T> tree(block: (File) -> T): T {
    val root = Files.createTempDirectory("local-media-cov").toFile()
    try { return block(root) } finally { root.deleteRecursively() }
  }
  private fun File.touch(name: String) = File(this, name).also { it.parentFile.mkdirs(); it.writeText("x") }

  @Test fun classifyAndDisplayNameEdgeCases() {
    assertEquals(LocalMedia.Kind.IMAGE, LocalMedia.classify("A.HEIC"))
    assertEquals(LocalMedia.Kind.VIDEO, LocalMedia.classify("clip.Ts"))
    assertEquals(LocalMedia.Kind.OTHER, LocalMedia.classify("jpg"))
    assertEquals(LocalMedia.Kind.OTHER, LocalMedia.classify("photo.jpg.txt"))
    assertEquals(LocalMedia.Kind.OTHER, LocalMedia.classify(""))
    assertEquals("/", LocalMedia.displayName("/"))
    assertEquals("Trip", LocalMedia.displayName("/storage/emulated/0/DCIM/Trip///"))
    assertEquals("plain", LocalMedia.displayName("plain"))
  }

  @Test fun junkDirsAreCaseInsensitive() {
    for (name in listOf(".thumbnails", "ANDROID", "Cache", "screenshots")) assertTrue(name, LocalMedia.isJunkDir(name))
    for (name in listOf("DCIM", "Androids", "my cache")) assertFalse(name, LocalMedia.isJunkDir(name))
  }

  @Test fun accessibilityRequiresAReadableDirectory() = tree { root ->
    assertTrue(LocalMedia.isAccessible(root.path))
    assertFalse(LocalMedia.isAccessible(root.touch("a.jpg").path))
    assertFalse(LocalMedia.isAccessible(File(root, "missing").path))
    assertFalse(LocalMedia.isAccessible("\u0000bad"))
  }

  @Test fun enumerateHonoursNonPositiveLimitsAndNonDirectories() = tree { root ->
    root.touch("a.jpg")
    assertTrue(LocalMedia.enumerate(root.path, true, max = 0).isEmpty())
    assertTrue(LocalMedia.enumerate(root.path, true, maxScan = 0).isEmpty())
    assertTrue(LocalMedia.enumerate(File(root, "a.jpg").path, true).isEmpty())
    assertTrue(LocalMedia.enumerate(File(root, "missing").path, true).isEmpty())
  }

  @Test fun enumerateSkipsVideosWhenExcludedAndJunkSubfolders() = tree { root ->
    root.touch("b.mp4"); root.touch("a.JPG"); root.touch("notes.txt")
    root.touch("Sub/c.png"); root.touch(".hidden/d.jpg"); root.touch("Sub/Screenshots/e.jpg"); root.touch("cache/f.jpg")
    val photos = LocalMedia.enumerate(root.path, includeVideo = false)
    assertEquals(listOf("a.JPG", "c.png"), photos.map { File(it.path).name })
    assertTrue(photos.none { it.isVideo })
    val all = LocalMedia.enumerate(root.path, includeVideo = true)
    assertEquals(listOf("a.JPG", "b.mp4", "c.png"), all.map { File(it.path).name })
    assertEquals(listOf(false, true, false), all.map { it.isVideo })
  }

  @Test fun enumerateStopsAtTheScanBudgetCountingDirectoryEntries() = tree { root ->
    root.touch("a.txt"); root.touch("b.txt"); root.touch("c.jpg")
    assertTrue(LocalMedia.enumerate(root.path, true, maxScan = 2).isEmpty())
    assertEquals(1, LocalMedia.enumerate(root.path, true, maxScan = 3).size)
  }

  @Test fun enumerateCapsAtMax() = tree { root ->
    (1..5).forEach { root.touch("p$it.jpg") }
    assertEquals(listOf("p1.jpg", "p2.jpg"), LocalMedia.enumerate(root.path, true, max = 2).map { File(it.path).name })
  }

  @Test fun summarizeEdgeLimits() = tree { root ->
    root.touch("a.jpg")
    assertEquals(MediaSummary(0, 0, false, emptyList()), LocalMedia.summarize(File(root, "a.jpg").path))
    assertEquals(MediaSummary(0, 0, true, emptyList()), LocalMedia.summarize(root.path, maxCount = 0))
    assertEquals(MediaSummary(0, 0, true, emptyList()), LocalMedia.summarize(root.path, maxScan = 0))
  }

  @Test fun summarizeCountsBothKindsAndLimitsSamples() = tree { root ->
    root.touch("a.jpg"); root.touch("b.mov"); root.touch("c.txt"); root.touch("Nested/d.webp"); root.touch("Android/e.jpg")
    val s = LocalMedia.summarize(root.path, sampleCount = 1)
    assertEquals(2, s.photos)
    assertEquals(1, s.videos)
    assertEquals(3, s.total)
    assertFalse(s.capped)
    assertEquals(1, s.samples.size)
    val none = LocalMedia.summarize(root.path, sampleCount = 0)
    assertTrue(none.samples.isEmpty())
  }

  @Test fun summarizeCapsByCountOrScan() = tree { root ->
    (1..4).forEach { root.touch("v$it.mp4") }
    val byCount = LocalMedia.summarize(root.path, maxCount = 2)
    assertTrue(byCount.capped)
    assertEquals(2, byCount.videos)
    assertTrue(byCount.samples.all { it.isVideo })
    val byScan = LocalMedia.summarize(root.path, maxScan = 3)
    assertTrue(byScan.capped)
    assertEquals(3, byScan.total)
  }

  @Test fun storageRootsOnlyListsReadableVolumes() {
    // On a JVM host without /storage this is empty; on any host every root must be readable.
    for (root in LocalMedia.storageRoots()) assertTrue(root.path, File(root.path).canRead())
  }
}
