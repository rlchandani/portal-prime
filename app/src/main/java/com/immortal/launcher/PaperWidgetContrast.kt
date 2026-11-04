/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.pow

/** Local widget plates on the Paper photo surface; foreground alpha remains user-owned. */
internal object PaperWidgetContrast {
  const val DARK = 0xE6293029.toInt()
  const val LIGHT = 0xF2F7F3E9.toInt()
  const val PAPER = 0xFFEDE9DF.toInt()

  fun backing(foreground: Int): Int =
      if (contrast(foreground, LIGHT) > contrast(foreground, DARK)) LIGHT else DARK

  data class Widget(val top: Int, val bottom: Int, val foreground: Int)
  data class Band(val top: Int, val bottom: Int, val color: Int)

  /** Adjacent widgets with the same ink tone share one plate; mixed tones stay independent. */
  fun bands(height: Int, widgets: List<Widget>): List<Band> {
    if (height <= 0 || widgets.isEmpty()) return emptyList()
    val result = mutableListOf<Band>()
    var top = 0
    widgets.forEachIndexed { index, widget ->
      val bottom = if (index == widgets.lastIndex) height
          else ((widget.bottom.toLong() + widgets[index + 1].top) / 2)
              .coerceIn(top.toLong(), height.toLong()).toInt()
      val color = backing(widget.foreground)
      if (bottom > top) {
        val previous = result.lastOrNull()
        if (previous?.color == color) {
          result[result.lastIndex] = previous.copy(bottom = bottom)
        } else result.add(Band(top, bottom, color))
      }
      top = bottom
    }
    return result
  }

  /** Contrast of the actually composited text, rather than its unattenuated RGB value. */
  fun contrast(foreground: Int, backing: Int): Double {
    val plate = composite(backing, PAPER)
    val ink = composite(foreground, plate)
    val a = luminance(ink)
    val b = luminance(plate)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
  }

  private fun composite(foreground: Int, background: Int): Int {
    val alpha = (foreground ushr 24) / 255.0
    fun channel(shift: Int): Int =
        (((foreground ushr shift and 255) * alpha +
            (background ushr shift and 255) * (1.0 - alpha)) + 0.5).toInt()
    return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
  }

  private fun luminance(color: Int): Double {
    fun linear(shift: Int): Double {
      val channel = (color ushr shift and 255) / 255.0
      return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
  }
}
