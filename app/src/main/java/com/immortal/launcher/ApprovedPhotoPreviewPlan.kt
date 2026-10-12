/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

internal data class ApprovedSourcePreviewSize(val width: Int, val height: Int)
internal data class ApprovedPhotoPreviewPlan(
    val width: Int, val height: Int, val left: Int, val top: Int,
    val cropWidth: Int, val cropHeight: Int,
)

/** Centered ContentScale.Crop geometry in physical pixels, without enlarging a small original. */
internal fun approvedPhotoPreviewPlan(width: Int, height: Int, target: ApprovedSourcePreviewSize): ApprovedPhotoPreviewPlan? {
    if (width <= 0 || height <= 0 || target.width !in 1..2048 || target.height !in 1..2048) return null
    val scale = minOf(1.0, max(target.width.toDouble() / width, target.height.toDouble() / height),
        SlideshowDecodeBudget.MAX_EDGE.toDouble() / max(width, height),
        sqrt(SlideshowDecodeBudget.MAX_PIXELS.toDouble() / (width.toLong() * height)))
    var w = ceil(width * scale).toInt().coerceIn(1, SlideshowDecodeBudget.MAX_EDGE)
    var h = ceil(height * scale).toInt().coerceIn(1, SlideshowDecodeBudget.MAX_EDGE)
    // Ceil may add one pixel on both axes at the hard pixel limit.
    while (w.toLong() * h > SlideshowDecodeBudget.MAX_PIXELS) {
        if (w >= h) w-- else h--
    }
    val wide = w.toLong() * target.height >= h.toLong() * target.width
    val cropW = if (wide) (h.toLong() * target.width / target.height).toInt().coerceIn(1, w) else w
    val cropH = if (wide) h else (w.toLong() * target.height / target.width).toInt().coerceIn(1, h)
    return ApprovedPhotoPreviewPlan(w, h, (w - cropW) / 2, (h - cropH) / 2, cropW, cropH)
}
