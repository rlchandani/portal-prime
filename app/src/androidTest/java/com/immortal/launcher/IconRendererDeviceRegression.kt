/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Native pixel checks; call with the target app's context, including against an older APK. */
internal object IconRendererDeviceRegression {
  fun verify(context: Context): JSONObject {
    val checks = JSONObject()
    val measurements = JSONObject()
    val failures = JSONArray()
    fun expect(name: String, condition: Boolean) {
      checks.put(name, condition)
      if (!condition) failures.put(name)
    }
    fun scenario(name: String, body: () -> Unit) {
      try {
        body()
      } catch (error: Exception) {
        expect("${name}_completed", false)
        measurements.put("${name}_error", error.javaClass.simpleName)
      }
    }

    scenario("legacy") {
      val source = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
      source.eraseColor(Color.rgb(35, 98, 210))
      // A real raster foreground marker catches lost content and broken legacy scaling.
      for (y in 10..13) for (x in 10..13) source.setPixel(x, y, Color.GREEN)
      try {
        val output = IconRenderer.renderAppIcon(context, BitmapDrawable(context.resources, source))
        try {
          expect("default_is_144px", output.width == 144 && output.height == 144)
          val corners = cornerAlphas(output)
          measurements.put("legacy_corner_alphas", JSONArray(corners.toList()))
          expect("legacy_corners_transparent", corners.all { it == 0 })
          expect("legacy_center_content_preserved", output.getPixel(72, 72) == Color.GREEN)
          expect("legacy_edge_content_preserved", output.getPixel(72, 8) == Color.rgb(35, 98, 210))
          // These points separate a broad superellipse silhouette from a circle.
          expect("legacy_shoulder_opaque", Color.alpha(output.getPixel(14, 14)) == 255)
          expect("legacy_outer_corner_clear", Color.alpha(output.getPixel(7, 7)) == 0)
          var partialAlphaPixels = 0
          for (y in 0 until output.height) for (x in 0 until output.width) {
            if (Color.alpha(output.getPixel(x, y)) in 1..254) partialAlphaPixels++
          }
          measurements.put("legacy_partial_alpha_pixels", partialAlphaPixels)
          expect("legacy_boundary_antialiased", partialAlphaPixels > 0)
        } finally {
          output.recycle()
        }
      } finally {
        source.recycle()
      }
    }

    scenario("translucent_legacy") {
      val output = IconRenderer.renderAppIcon(context, ColorDrawable(Color.argb(128, 20, 80, 160)), 96)
      try {
        expect("legacy_source_alpha_preserved", Color.alpha(output.getPixel(48, 48)) == 128)
        expect("translucent_corners_transparent", cornerAlphas(output).all { it == 0 })
      } finally {
        output.recycle()
      }
    }

    scenario("adaptive") {
      expect("adaptive_api_supported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val drawable = AdaptiveIconDrawable(
            ColorDrawable(Color.BLUE),
            InsetDrawable(ColorDrawable(Color.GREEN), .4f),
        )
        val output = IconRenderer.renderAppIcon(context, drawable)
        try {
          measurements.put("adaptive_corner_alphas", JSONArray(cornerAlphas(output).toList()))
          expect("adaptive_corners_transparent", cornerAlphas(output).all { it == 0 })
          expect("adaptive_foreground_center_preserved", output.getPixel(72, 72) == Color.GREEN)
          expect("adaptive_background_preserved", output.getPixel(72, 8) == Color.BLUE)
        } finally {
          output.recycle()
        }
      }
    }

    scenario("bundled_gradient") {
      // Resolve in the installed target; a test-only APK may have newer, shifted resource IDs.
      val resource = context.resources.getIdentifier("icon_browser_tile", "drawable", context.packageName)
      require(resource != 0) { "Installed target has no bundled browser vector" }
      val drawable = requireNotNull(context.getDrawable(resource))
      val output = IconRenderer.renderAppIcon(context, drawable)
      try {
        measurements.put("gradient_corner_alphas", JSONArray(cornerAlphas(output).toList()))
        expect("gradient_corners_transparent", cornerAlphas(output).all { it == 0 })
        expect("gradient_center_opaque", Color.alpha(output.getPixel(72, 72)) == 255)
        val upper = output.getPixel(28, 28)
        val lower = output.getPixel(115, 115)
        measurements.put("gradient_upper_argb", upper)
        measurements.put("gradient_lower_argb", lower)
        expect("gradient_colors_preserved",
            Color.alpha(upper) == 255 && Color.alpha(lower) == 255 &&
                Color.blue(upper) > Color.red(upper) &&
                Color.blue(lower) > Color.red(lower) && upper != lower)
      } finally {
        output.recycle()
      }
    }

    scenario("repeated") {
      val reference = IconRenderer.renderAppIcon(context, ColorDrawable(Color.RED))
      try {
        // More than four distinct sizes exercises bounded-mask eviction and an oversized mask.
        val sizes = intArrayOf(64, 96, 144, 192, 256, 288, 144, 64, 256, 96, 192, 144)
        var sizesCorrect = true
        var cornersClear = true
        var centersCorrect = true
        sizes.forEachIndexed { index, size ->
          val color = if (index % 2 == 0) Color.BLUE else Color.GREEN
          val output = IconRenderer.renderAppIcon(context, ColorDrawable(color), size)
          try {
            sizesCorrect = sizesCorrect && output.width == size && output.height == size
            cornersClear = cornersClear && cornerAlphas(output).all { it == 0 }
            centersCorrect = centersCorrect && output.getPixel(size / 2, size / 2) == color
            // Returned content must never be the cached mask or a shared output buffer.
            output.eraseColor(Color.MAGENTA)
          } finally {
            output.recycle()
          }
        }
        expect("repeated_dimensions_correct", sizesCorrect)
        expect("repeated_corners_transparent", cornersClear)
        expect("repeated_centers_correct", centersCorrect)
        expect("earlier_output_unchanged", reference.getPixel(72, 72) == Color.RED &&
            cornerAlphas(reference).all { it == 0 })
        val repeated = IconRenderer.renderAppIcon(context, ColorDrawable(Color.RED))
        try {
          expect("repeated_output_independent", repeated !== reference)
          expect("repeated_pixels_identical", repeated.sameAs(reference))
        } finally {
          repeated.recycle()
        }
        measurements.put("repeat_sizes", JSONArray(sizes.toList()))
      } finally {
        reference.recycle()
      }
    }

    scenario("invalid_size") {
      for (size in intArrayOf(0, -1)) {
        val output = IconRenderer.renderAppIcon(context, ColorDrawable(Color.RED), size)
        try {
          expect("invalid_size_${size}_transparent",
              output.width == 1 && output.height == 1 && Color.alpha(output.getPixel(0, 0)) == 0)
        } finally {
          output.recycle()
        }
      }
    }
    return JSONObject()
        .put("passed", failures.length() == 0)
        .put("checks", checks)
        .put("measurements", measurements)
        .put("failures", failures)
  }

  /** Four 2x2 corner patches catch residual color beyond the requested silhouette. */
  private fun cornerAlphas(bitmap: Bitmap): IntArray {
    val result = IntArray(16)
    var index = 0
    for (y in intArrayOf(0, 1, bitmap.height - 2, bitmap.height - 1)) {
      for (x in intArrayOf(0, 1, bitmap.width - 2, bitmap.width - 1)) {
        result[index++] = Color.alpha(bitmap.getPixel(x, y))
      }
    }
    return result
  }
}
