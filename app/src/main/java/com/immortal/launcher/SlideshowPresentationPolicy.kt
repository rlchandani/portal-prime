/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * View-independent Gallery placement. Only geometry is allocated; no image work or timers.
 * Overlay search visits at most 32 obstacles and retains at most 16 candidate rectangles.
 */
internal object SlideshowPresentationPolicy {
  const val MAX_OCCLUSIONS = 32
  private const val MAX_CANDIDATES = 16
  data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
  }
  data class Plan(val area: Bounds, val path: SlideshowGeometry.Motion)

  fun galleryPrint(
      imageWidth: Int, imageHeight: Int, viewportWidth: Int, viewportHeight: Int,
      density: Float = 1f, occupied: List<Bounds> = emptyList(),
      adjustment: SlideshowAdjustment? = null,
  ): Plan {
    if (imageWidth <= 0 || imageHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
      val empty = SlideshowGeometry.Transform(0f, 0f, 0f)
      return Plan(Bounds(0, 0, 0, 0), SlideshowGeometry.Motion(empty, empty, true))
    }
    val viewport = Bounds(0, 0, viewportWidth, viewportHeight)
    val safeDensity = if (density.isFinite() && density > 0f) density.coerceAtMost(4f) else 1f
    fun inset(area: Bounds): Bounds {
      // Four percent of the short edge, capped at 56dp, and always leave a positive viewport.
      val short = minOf(area.width, area.height)
      val margin = minOf((short * .04).toInt(), (56 * safeDensity).toInt(), (short - 1) / 2)
      return Bounds(area.left + margin, area.top + margin, area.right - margin, area.bottom - margin)
    }
    fun usefulScale(area: Bounds): Double {
      val print = inset(area)
      return minOf(print.width.toDouble() / imageWidth, print.height.toDouble() / imageHeight)
    }
    val rank = compareByDescending<Bounds> { usefulScale(it) }
        .thenByDescending { it.width.toLong() * it.height }
    val area = inset(freeArea(viewport, occupied, rank, adjustment == null))
    val manual = adjustment?.transform(imageWidth, imageHeight, area.width, area.height)
    val path = if (manual != null) SlideshowGeometry.Motion(manual, manual, adjustment.zoom == 1f)
    else SlideshowGeometry.plan(imageWidth, imageHeight, area.width, area.height,
        emptyList(), fill = false, motion = false)
    return Plan(area, path)
  }

  /** Equal tiles in one safe spread; normal presentation always contains the entire photos. */
  fun gallerySpread(
      images: List<Pair<Int, Int>>, viewportWidth: Int, viewportHeight: Int,
      occupied: List<Bounds> = emptyList(),
  ): List<Plan> {
    val sizes = images.take(2)
    if (sizes.isEmpty()) return emptyList()
    if (viewportWidth <= 0 || viewportHeight <= 0 || sizes.any { it.first <= 0 || it.second <= 0 }) {
      val empty = SlideshowGeometry.Transform(0f, 0f, 0f)
      return sizes.map { Plan(Bounds(0, 0, 0, 0), SlideshowGeometry.Motion(empty, empty, true)) }
    }
    val viewport = Bounds(0, 0, viewportWidth, viewportHeight)
    val short = minOf(viewportWidth, viewportHeight)
    val matte = (short * .03).toInt()
    val gutter = (short * .02).toInt()
    fun tiles(area: Bounds): List<Bounds> {
      val margin = minOf(matte, (area.width - sizes.size).coerceAtLeast(0) / 2,
          (area.height - 1).coerceAtLeast(0) / 2)
      val inner = Bounds(area.left + margin, area.top + margin,
          area.right - margin, area.bottom - margin)
      val gap = if (sizes.size == 2) minOf(gutter, (inner.width - 2).coerceAtLeast(0)) else 0
      val tileWidth = (inner.width - gap) / sizes.size
      return sizes.indices.map { index ->
        val left = inner.left + index * (tileWidth + gap)
        Bounds(left, inner.top, left + tileWidth, inner.bottom)
      }
    }
    val rank = compareByDescending<Bounds> { area ->
      tiles(area).mapIndexed { index, tile ->
        minOf(tile.width.toDouble() / sizes[index].first,
            tile.height.toDouble() / sizes[index].second)
      }.minOrNull() ?: 0.0
    }.thenByDescending { it.width.toLong() * it.height }
    return tiles(freeArea(viewport, occupied, rank)).mapIndexed { index, area ->
      Plan(area, SlideshowGeometry.plan(sizes[index].first, sizes[index].second,
          area.width, area.height, emptyList(), fill = false, motion = false))
    }
  }

  /** Shared bounded reservation search for print and spread; impossible reservations fall back. */
  private fun freeArea(
      viewport: Bounds, occupied: List<Bounds>, rank: Comparator<Bounds>,
      reserve: Boolean = true,
  ): Bounds {
    var candidates = listOf(viewport)
    // Manual framing keeps its chosen focus; impossible/excessive reservations keep a usable
    // whole print rather than shrinking it to nothing or walking unbounded metadata.
    if (reserve && occupied.size <= MAX_OCCLUSIONS) {
      occupied.forEach { raw ->
        val obstacle = Bounds(raw.left.coerceIn(0, viewport.right), raw.top.coerceIn(0, viewport.bottom),
            raw.right.coerceIn(0, viewport.right), raw.bottom.coerceIn(0, viewport.bottom))
        if (obstacle.left >= obstacle.right || obstacle.top >= obstacle.bottom) return@forEach
        candidates = candidates.flatMap { area ->
          if (area.left >= obstacle.right || area.right <= obstacle.left ||
              area.top >= obstacle.bottom || area.bottom <= obstacle.top) listOf(area)
          else listOf(
              Bounds(area.left, area.top, area.right, minOf(area.bottom, obstacle.top)),
              Bounds(area.left, maxOf(area.top, obstacle.bottom), area.right, area.bottom),
              Bounds(area.left, area.top, minOf(area.right, obstacle.left), area.bottom),
              Bounds(maxOf(area.left, obstacle.right), area.top, area.right, area.bottom))
              .filter { it.left < it.right && it.top < it.bottom }
        }.distinct().sortedWith(rank).take(MAX_CANDIDATES)
      }
    }
    return candidates.firstOrNull() ?: viewport
  }
}
