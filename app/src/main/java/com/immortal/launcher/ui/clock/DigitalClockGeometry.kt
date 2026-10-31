/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.ui.clock

import kotlin.math.ceil

/** Clock geometry in physical pixels; font metrics are measured at one pixel. */
internal object DigitalClockGeometry {
    data class Size(val textSize: Float, val width: Int, val height: Int)

    fun measure(
        density: Float,
        scale: Int,
        widthPerPixel: Float,
        heightPerPixel: Float,
        maxWidth: Int,
        maxHeight: Int,
        padding: Float,
    ): Size {
        if (maxWidth <= 0 || maxHeight <= 0) return Size(0f, 0, 0)
        val inset = padding.coerceIn(0f, minOf(maxWidth, maxHeight) / 2f)
        val selectedScale = scale.coerceAtLeast(1) / 100f
        // Fit the whole large-clock family together. Otherwise Medium and Large both
        // saturate at the same width on portrait panels and the selector stops working.
        val familyScale = maxOf(if (scale > 100) 5f else 1f, selectedScale)
        val widthUnit = widthPerPixel.coerceAtLeast(0.001f)
        val heightUnit = heightPerPixel.coerceAtLeast(0.001f)
        val baseSize = minOf(
            85f * density,
            (maxWidth - inset * 2f) / (widthUnit * familyScale),
            (maxHeight - inset * 2f) / (heightUnit * familyScale),
        ).coerceAtLeast(0f)
        val textSize = baseSize * selectedScale
        return Size(
            textSize,
            ceil(textSize * widthUnit + inset * 2f).toInt().coerceAtMost(maxWidth),
            ceil(textSize * heightUnit + inset * 2f).toInt().coerceAtMost(maxHeight),
        )
    }
}
