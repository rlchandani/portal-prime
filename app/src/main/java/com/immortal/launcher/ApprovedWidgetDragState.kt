/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Pure drag session: viewport pointer positions, content bounds and release-only destinations. */
internal class ApprovedWidgetDragState {
  data class Point(val x: Float, val y: Float)
  data class Bounds(val key: String, val left: Float, val top: Float, val width: Float, val height: Float)
  data class Snapshot(
      val key: String,
      val translationX: Float,
      val translationY: Float,
      val pointerY: Float,
      val targetKey: String?,
  )
  private data class Session(
      val owner: Any,
      val source: Bounds,
      val startPointer: Point,
      val startScroll: Float,
      var pointer: Point,
  )
  private var session: Session? = null

  fun start(owner: Any, source: Bounds, pointer: Point, scroll: Float): Boolean {
    if (session != null) return false
    session = Session(owner, source, pointer, scroll, pointer)
    return true
  }

  fun isOwner(owner: Any): Boolean = session?.owner === owner

  fun move(owner: Any, pointer: Point): Boolean {
    val active = session ?: return false
    if (active.owner !== owner) return false
    active.pointer = pointer
    return true
  }

  fun snapshot(scroll: Float, targets: List<Bounds>): Snapshot? {
    val active = session ?: return null
    val x = active.pointer.x - active.startPointer.x
    // Translation is in content coordinates. Adding the scroll delta keeps the ghost
    // stationary in the viewport when only the viewport scrolls beneath the finger.
    val y = active.pointer.y - active.startPointer.y + scroll - active.startScroll
    val centerX = active.source.left + active.source.width / 2 + x
    val centerY = active.source.top + active.source.height / 2 + y
    val target = targets.firstOrNull {
      centerX >= it.left && centerX <= it.left + it.width &&
          centerY >= it.top && centerY <= it.top + it.height
    }?.key
    return Snapshot(active.source.key, x, y, active.pointer.y, target)
  }

  fun finish(owner: Any, scroll: Float, targets: List<Bounds>): String? {
    if (!isOwner(owner)) return null
    val preview = snapshot(scroll, targets)
    reset()
    return preview?.targetKey?.takeIf { it != preview.key }
  }

  fun cancel(owner: Any) { if (isOwner(owner)) reset() }
  fun reset() { session = null }

  companion object {
    fun edgeScrollDelta(
        pointerY: Float, viewportHeight: Float, edgeBand: Float, maxSpeed: Float,
        elapsedSeconds: Float, scroll: Float, maxScroll: Float,
    ): Float {
      val band = minOf(edgeBand, viewportHeight / 2)
      if (band <= 0f || maxScroll <= 0f) return 0f
      val edge = when {
        pointerY < band -> -((band - pointerY) / band).coerceIn(0f, 1f)
        pointerY > viewportHeight - band ->
          ((pointerY - (viewportHeight - band)) / band).coerceIn(0f, 1f)
        else -> 0f
      }
      val velocity = edge * kotlin.math.abs(edge) * maxSpeed
      // A delayed frame cannot jump the viewport; ScrollState owns the actual clamp.
      return (velocity * elapsedSeconds.coerceIn(0f, .05f))
          .coerceIn(-scroll.coerceAtLeast(0f), (maxScroll - scroll).coerceAtLeast(0f))
    }
  }
}
