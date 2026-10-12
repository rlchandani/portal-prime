/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File
import java.util.concurrent.Semaphore

private val approvedSourcesDecodeSlots = Semaphore(2)
private val approvedSourcesPreviewCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

/**
 * Source previews keep the media's aspect ratio for the approved 3:2 cells. The existing square
 * thumbnails cannot supply that crop. Bound decoded bytes and concurrency, and run only on IO.
 */
private fun approvedSourcesPreviewKey(item: MediaItem, target: ApprovedSourcePreviewSize?): String {
    val file = File(item.path)
    return "${item.path}:${file.lastModified()}:${file.length()}" +
        if (target == null) "" else ":${target.width}x${target.height}"
}

internal fun approvedSourcesThumbnail(item: MediaItem, target: ApprovedSourcePreviewSize? = null,
    beforeDecode: () -> Unit = {}): Bitmap? {
    var key = ""
    return approvedSourcesPreviewWithSlots(approvedSourcesDecodeSlots, cached = {
        key = approvedSourcesPreviewKey(item, target)
        approvedSourcesPreviewCache.get(key)
    }, decode = {
        beforeDecode()
        val bitmap = runCatching {
            if (item.isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(item.path)
                    approvedVideoFrame(retriever)
                } finally { runCatching { retriever.release() } }
            } else approvedPhotoPreview(File(item.path), target)
        }.getOrNull()
        if (Thread.currentThread().isInterrupted) {
            bitmap?.recycle()
            throw InterruptedException()
        }
        bitmap?.let { approvedSourcesPreviewCache.put(key, it) }
        bitmap
    })
}

/** Removes only a caller-owned generated fixture; normal preview ownership is unchanged. */
internal fun approvedSourcesDiscardPreview(item: MediaItem, target: ApprovedSourcePreviewSize? = null): Bitmap? =
    approvedSourcesPreviewCache.remove(approvedSourcesPreviewKey(item, target))
