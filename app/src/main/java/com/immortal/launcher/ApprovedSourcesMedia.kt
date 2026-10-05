/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File

internal data class ApprovedSourcesFolderStatus(val accessible: Boolean, val summary: MediaSummary?)

/**
 * Picker-only, bounded scan using LocalMedia's formats and directory policy. Its blocking file
 * calls run on IO; interruption checkpoints stop traversing after the owning lazy item leaves.
 */
internal fun approvedSourcesSummary(
    path: String, sampleCount: Int = 3, maxCount: Int = 600, maxScan: Int = 4000,
): MediaSummary {
    var photos = 0
    var videos = 0
    var scanned = 0
    val samples = ArrayList<MediaItem>()
    val stack = ArrayDeque<File>()
    val root = File(path)
    approvedSourcesCheckInterrupted()
    if (!LocalMedia.isAccessible(path)) return MediaSummary(0, 0, false, emptyList())
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        approvedSourcesCheckInterrupted()
        val children = runCatching { stack.removeLast().listFiles() }.getOrNull() ?: continue
        for (file in children) {
            approvedSourcesCheckInterrupted()
            // Count directories too: a tree of empty folders must not be an unbounded scan.
            scanned++
            if (file.isDirectory) {
                if (!LocalMedia.isJunkDir(file.name)) stack.addLast(file)
            } else {
                val kind = LocalMedia.classify(file.name)
                if (kind == LocalMedia.Kind.IMAGE) photos++
                if (kind == LocalMedia.Kind.VIDEO) videos++
                if (kind != LocalMedia.Kind.OTHER && samples.size < sampleCount)
                    samples.add(MediaItem(file.absolutePath, kind == LocalMedia.Kind.VIDEO))
            }
            if (photos + videos >= maxCount || scanned >= maxScan)
                return MediaSummary(photos, videos, true, samples)
        }
    }
    return MediaSummary(photos, videos, false, samples)
}

internal fun approvedSourcesCheckInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException()
}
