/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File
import java.util.concurrent.Semaphore
import kotlin.math.max

private val approvedSourcesDecodeSlots = Semaphore(2)
private val approvedSourcesPreviewCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

/**
 * Source previews keep the media's aspect ratio for the approved 3:2 cells. The existing square
 * thumbnails cannot supply that crop. Bound decoded bytes and concurrency, and run only on IO.
 */
internal fun approvedSourcesThumbnail(item: MediaItem): Bitmap? {
    approvedSourcesDecodeSlots.acquire()
    return try {
        approvedSourcesCheckInterrupted()
        val file = File(item.path)
        val key = "${item.path}:${file.lastModified()}:${file.length()}"
        approvedSourcesPreviewCache.get(key)?.let { return it }
        val bitmap = runCatching {
            if (item.isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(item.path)
                    retriever.getFrameAtTime(0)?.let { frame ->
                        val edge = max(frame.width, frame.height)
                        if (edge <= 512) frame else {
                            val width = (frame.width * 512L / edge).toInt().coerceAtLeast(1)
                            val height = (frame.height * 512L / edge).toInt().coerceAtLeast(1)
                            Bitmap.createScaledBitmap(frame, width, height, true).also { frame.recycle() }
                        }
                    }
                } finally { runCatching { retriever.release() } }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(item.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
                    BitmapFactory.decodeFile(item.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        }.getOrNull()
        if (Thread.currentThread().isInterrupted) {
            bitmap?.recycle()
            throw InterruptedException()
        }
        bitmap?.let { approvedSourcesPreviewCache.put(key, it) }
        bitmap
    } finally {
        approvedSourcesDecodeSlots.release()
    }
}
