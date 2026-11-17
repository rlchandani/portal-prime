/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Where a face draws its time on the photo, after the face's own layout. */
internal enum class FacePlacement { CORNER_CLASSIC, CENTER, FLIP, CORNER_SMALL }

internal data class ClockFacePreviewSpec(
    val shown: Boolean,
    val placement: FacePlacement,
    /** Time size in sp at this preview's scale, after the face's Small/Medium/Large. */
    val timeSp: Float,
    /** Date size, or null when the face shows no date. */
    val dateSp: Float?,
    val condensed: Boolean,
    val caption: String,
)

/**
 * Clock Faces › Live preview, pure: a scaled stand-in for each [FaceCatalog] face (the photo
 * frame draws the real one), sized by the saved size variant relative to Medium.
 */
internal object ClockFacePreview {
    private const val MEDIUM = 1
    private const val DATE_RATIO = .3f

    private data class Base(val placement: FacePlacement, val timeSp: Float, val date: Boolean, val condensed: Boolean = false)

    private val bases = mapOf(
        FaceCatalog.DEFAULT_ID to Base(FacePlacement.CORNER_CLASSIC, 72f, date = true),
        "flip" to Base(FacePlacement.FLIP, 96f, date = false),
        "big" to Base(FacePlacement.CENTER, 130f, date = false),
        "bold" to Base(FacePlacement.CENTER, 120f, date = true, condensed = true),
        "minimal" to Base(FacePlacement.CORNER_SMALL, 46f, date = false),
    )

    /** Size variant relative to Medium (1 for fixed-size faces). */
    fun sizeFactor(entry: FaceCatalog.Entry, sizeIndex: Int): Float {
        if (entry.sizes.size <= MEDIUM) return 1f
        val index = sizeIndex.coerceIn(0, entry.sizes.lastIndex)
        return entry.sizes[index].toFloat() / entry.sizes[MEDIUM]
    }

    fun spec(facesEnabled: Boolean, faceId: String?, sizeIndex: Int): ClockFacePreviewSpec {
        val entry = FaceCatalog.entryFor(faceId)
        val base = bases[entry.id] ?: bases.getValue(FaceCatalog.DEFAULT_ID)
        val factor = sizeFactor(entry, sizeIndex)
        val sizeLabel = if (entry.sizes.isEmpty()) null
            else FaceCatalog.SIZE_LABELS[sizeIndex.coerceIn(0, FaceCatalog.SIZE_LABELS.lastIndex)]
        val caption = if (!facesEnabled) "Clock face hidden"
            else listOfNotNull(entry.name, sizeLabel, "live").joinToString(" · ")
        val time = base.timeSp * factor
        return ClockFacePreviewSpec(facesEnabled, base.placement, time, if (base.date) time * DATE_RATIO else null,
            base.condensed, caption)
    }
}
