/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import kotlin.math.max

/** Portal firmware supports exact scaled output; older Android keeps the sampled compatibility path. */
internal fun approvedPhotoPreview(file: File, target: ApprovedSourcePreviewSize?): Bitmap? {
    if (target != null && Build.VERSION.SDK_INT >= 28) return approvedPortalPhotoPreview(file, target)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Release only the owned intermediate; cached results retain normal caller/UI ownership. */
internal fun approvedCroppedPhotoPreview(sampled: Bitmap, plan: ApprovedPhotoPreviewPlan,
    crop: (Bitmap, ApprovedPhotoPreviewPlan) -> Bitmap = { bitmap, geometry ->
        val output = Bitmap.createBitmap(geometry.cropWidth, geometry.cropHeight, Bitmap.Config.ARGB_8888)
        try {
            Canvas(output).drawBitmap(bitmap, null, RectF(-geometry.left.toFloat(), -geometry.top.toFloat(),
                (geometry.width - geometry.left).toFloat(), (geometry.height - geometry.top).toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG))
            output
        } catch (failure: Throwable) { output.recycle(); throw failure }
    }): Bitmap = try {
    if (plan.left == 0 && plan.top == 0 &&
        plan.cropWidth == sampled.width && plan.cropHeight == sampled.height) sampled
    else crop(sampled, plan).also { if (it !== sampled) sampled.recycle() }
} catch (failure: Throwable) {
    sampled.recycle()
    throw failure
}

@RequiresApi(28)
private fun approvedPortalPhotoPreview(file: File, target: ApprovedSourcePreviewSize): Bitmap {
    var plan: ApprovedPhotoPreviewPlan? = null
    val sampled = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val geometry = approvedPhotoPreviewPlan(info.size.width, info.size.height, target)
            ?: throw IllegalArgumentException("invalid_preview_size")
        plan = geometry
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        var sample = 1
        while (sample < (1 shl 29) &&
            info.size.width.toLong() / (sample * 2) >= geometry.width &&
            info.size.height.toLong() / (sample * 2) >= geometry.height) sample *= 2
        decoder.setTargetSampleSize(sample)
        // Portal's combined arbitrary resize/crop reproduces Android issue 139258905.
        // Sample directly, then draw the centered crop into one cell-sized bitmap.
    }
    val geometry = plan ?: run { sampled.recycle(); error("preview_header_unavailable") }
    return approvedCroppedPhotoPreview(sampled, geometry)
}
