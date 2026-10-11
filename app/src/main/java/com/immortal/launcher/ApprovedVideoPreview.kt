/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlin.math.max

/** A bounded aspect-preserving preview; the retriever remains owned by its caller. */
internal fun approvedVideoFrame(retriever: MediaMetadataRetriever, api: Int = Build.VERSION.SDK_INT,
    maxEdge: Int = 512, scale: (Bitmap, Int, Int) -> Bitmap = { bitmap, width, height ->
        Bitmap.createScaledBitmap(bitmap, width, height, true)
    }): Bitmap? {
    require(maxEdge > 0)
    // Portal API 28/29 can request the small bitmap directly. Never retry a failed scaled
    // decode as a full-resolution frame: that would reintroduce the original memory spike.
    val frame = if (api >= 27) retriever.getScaledFrameAtTime(
        0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxEdge, maxEdge)
    else retriever.getFrameAtTime(0)
    if (frame == null) return null
    val edge = max(frame.width, frame.height)
    if (edge <= maxEdge) return frame
    val width = (frame.width * maxEdge.toLong() / edge).toInt().coerceAtLeast(1)
    val height = (frame.height * maxEdge.toLong() / edge).toInt().coerceAtLeast(1)
    return try {
        scale(frame, width, height).also { if (it !== frame) frame.recycle() }
    } catch (error: Throwable) {
        frame.recycle()
        throw error
    }
}
