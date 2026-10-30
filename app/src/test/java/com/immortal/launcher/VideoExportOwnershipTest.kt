/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoExportOwnershipTest {
  @get:Rule val temp = TemporaryFolder()

  @Test fun replacementCachePreservesPendingNativeFilesAndSweepsOrphans() {
    val dir = temp.newFolder()
    val cache = MediaCache(dir, 1_000_000)
    val target = cache.videoFile("video")
    val src = cache.uniqueTempFor(File(target.path + ".src")).apply { writeText("source") }
    val dst = cache.uniqueTempFor(target).apply { writeText("partial") }
    val orphan = File(dir, ".old.tmp").apply { writeText("orphan") }
    val lease = VideoExportOwnership.acquire(src, dst)!!
    try {
      MediaCache(dir, 1_000_000)
      assertTrue(src.exists())
      assertTrue(dst.exists())
      assertFalse(orphan.exists())
      assertFalse(VideoExportOwnership.isIdle)
      assertNull(VideoExportOwnership.acquire(temp.newFile(), temp.newFile()))
    } finally { lease.close() }
    MediaCache(dir, 1_000_000)
    assertFalse(src.exists())
    assertFalse(dst.exists())
  }

  @Test fun lateOldReleaseCannotFreeReplacementExport() {
    val old = VideoExportOwnership.acquire(temp.newFile(), temp.newFile())!!
    old.close()
    val newer = VideoExportOwnership.acquire(temp.newFile(), temp.newFile())!!
    try {
      old.close()
      assertFalse(VideoExportOwnership.isIdle)
      assertNull(VideoExportOwnership.acquire(temp.newFile(), temp.newFile()))
    } finally { newer.close() }
    assertTrue(VideoExportOwnership.isIdle)
  }

  @Test fun lateTempDeletionCannotDeleteNewWorkersFilesForSameVideo() {
    val cache = MediaCache(temp.newFolder(), 1_000_000)
    val target = cache.videoFile("same-video")
    val old = cache.uniqueTempFor(target).apply { writeText("old") }
    val newer = cache.uniqueTempFor(target).apply { writeText("new") }
    assertNotEquals(old, newer)
    old.delete()
    assertEquals("new", newer.readText())
    assertTrue(cache.commit(newer, target))
    assertEquals("new", target.readText())
  }

  @Test fun unrelatedExportDoesNotProtectFinishedWorkersTempFiles() {
    val oldSource = temp.newFile()
    val oldOutput = temp.newFile()
    val old = VideoExportOwnership.acquire(oldSource, oldOutput)!!
    old.close()
    val newSource = temp.newFile()
    val newOutput = temp.newFile()
    val newer = VideoExportOwnership.acquire(newSource, newOutput)!!
    try {
      assertFalse(VideoExportOwnership.isIdle)
      assertFalse(VideoExportOwnership.owns(oldSource))
      assertFalse(VideoExportOwnership.owns(oldOutput))
      assertTrue(VideoExportOwnership.owns(newSource))
      assertTrue(VideoExportOwnership.owns(newOutput))
    } finally { newer.close() }
  }
}
