/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedSourcesBoundedMediaScanTest {
    @Test fun countsPhotosAndVideosWithoutReadingMediaBytes() {
        val root = Files.createTempDirectory("approved-sources-media").toFile()
        try {
            root.resolve("photo.jpg").createNewFile()
            root.resolve("video.mp4").createNewFile()
            root.resolve("notes.txt").createNewFile()
            root.resolve(".cache").mkdir()
            root.resolve(".cache/hidden.jpg").createNewFile()
            val summary = approvedSourcesSummary(root.path)
            assertEquals(1, summary.photos)
            assertEquals(1, summary.videos)
            assertEquals(2, summary.samples.size)
            assertFalse(summary.capped)
        } finally { root.deleteRecursively() }
    }

    @Test fun scanBudgetAlsoBoundsTreesOfEmptyDirectories() {
        val root = Files.createTempDirectory("approved-sources-bounded").toFile()
        try {
            repeat(10) { root.resolve("empty-$it").mkdir() }
            val summary = approvedSourcesSummary(root.path, maxScan = 3)
            assertEquals(0, summary.total)
            assertTrue(summary.capped)
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedScanStopsBeforeTraversingStorage() {
        Thread.currentThread().interrupt()
        try {
            try {
                approvedSourcesSummary("/nonexistent")
                throw AssertionError("An interrupted scan must stop")
            } catch (_: InterruptedException) { /* expected */ }
        } finally { Thread.interrupted() }
    }
}
