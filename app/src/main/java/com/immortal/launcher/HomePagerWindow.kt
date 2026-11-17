/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Which Home widget pages stay composed around a slide, and which one does page work (producers,
 * lifecycle hooks, resume reads). The slide target only moves during the animation: it starts its
 * work, and the next page beyond it is composed, once the slide has settled. Frame 0 of a swipe
 * then only starts the animation instead of also restarting two pages' data loading.
 */
internal object HomePagerWindow {
  /** Pages kept composed on each side of the settled page. */
  const val NEIGHBORS = 1

  /** Sorted page indices to compose: the settled page and its neighbors, plus the slide target. */
  fun composed(target: Int, settled: Int, lastIndex: Int): List<Int> {
    if (lastIndex < 0) return emptyList()
    val anchor = settled.coerceIn(0, lastIndex)
    val around = (anchor - NEIGHBORS).coerceAtLeast(0)..(anchor + NEIGHBORS).coerceAtMost(lastIndex)
    return (around + target.coerceIn(0, lastIndex)).distinct().sorted()
  }

  /** Only the page the last slide settled on runs its producers and resume hooks. */
  fun working(index: Int, settled: Int): Boolean = index == settled
}
