/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * Lights changing on the plan, as the mock animates them (pure, unit tested): each light that
 * turned on or off (or changed brightness or colour) in a room fades its glow over [FADE_MS],
 * one after another [STAGGER_MS] apart, and the room's warm wash follows. While a fade runs the
 * cached floor layer leaves those rooms' light out ([rooms]) and the fade draws it instead.
 */
@Immutable
internal class CcLightFade(val lights: List<Light>, val washes: List<Wash>) {
  /** One light: where it sits (cells), its strength and colour before and after. 0 strength = off. */
  data class Light(val entityId: String, val roomId: String, val x: Float, val y: Float, val from: Float, val to: Float,
                   val fromRgb: Int, val toRgb: Int, val order: Int)

  /** A room's wash before and after: the strongest light's strength, 0 = no wash. */
  data class Wash(val roomId: String, val from: Float, val to: Float, val fromRgb: Int, val toRgb: Int)

  val rooms: Set<String> = (lights.map { it.roomId } + washes.map { it.roomId }).toSet()
  val durationMs: Int get() = FADE_MS + (lights.maxOfOrNull { it.order } ?: 0) * STAGGER_MS

  /** Light [l]'s own progress (0..1) when the whole fade is at [t] (0..1). */
  fun local(l: Light, t: Float): Float {
    val ms = t.coerceIn(0f, 1f) * durationMs - l.order * STAGGER_MS
    return smooth((ms / FADE_MS).coerceIn(0f, 1f))
  }

  /** The wash follows the room's last light. */
  fun washLocal(w: Wash, t: Float): Float {
    val last = lights.filter { it.roomId == w.roomId }.maxOfOrNull { it.order } ?: 0
    val ms = t.coerceIn(0f, 1f) * durationMs - last * STAGGER_MS
    return smooth((ms / FADE_MS).coerceIn(0f, 1f))
  }

  companion object {
    /** The mock's `.gl` transition (.9 s). */
    const val FADE_MS = 900
    /** The mock's 260 ms between lights. */
    const val STAGGER_MS = 260
    /** Lights beyond this many fade together (a whole-house change must not take ten seconds). */
    const val MAX_STAGGERED = 6

    /**
     * What changed between [before] and [after] (the same floor), or null when no light did.
     * Lights are matched by entity; a light that moved in the plan editor is not a fade.
     */
    fun diff(before: CcPlanModel, after: CcPlanModel): CcLightFade? {
      if (before.floor.id != after.floor.id || before.floor.rooms != after.floor.rooms) return null
      val lights = ArrayList<Light>()
      val washes = ArrayList<Wash>()
      for (v in after.views) {
        val old = before.view(v.room.id) ?: continue
        if (old.glows == v.glows) continue
        val was = old.glows.associateBy { it.entityId }
        val now = v.glows.associateBy { it.entityId }
        for (id in (was.keys + now.keys)) {
          val a = was[id]
          val b = now[id]
          if (a == b) continue
          val g = b ?: a ?: continue
          lights += Light(id, v.room.id, v.room.rect.x + g.fx * v.room.rect.w, v.room.rect.y + g.fy * v.room.rect.h,
              a?.strength ?: 0f, b?.strength ?: 0f, (a ?: g).rgb, (b ?: g).rgb, 0)
        }
        val wasB = old.glows.maxOfOrNull { it.strength } ?: 0f
        val nowB = v.glows.maxOfOrNull { it.strength } ?: 0f
        washes += Wash(v.room.id, wasB, nowB, CcOverviewText.mixRgb(old.glows.map { it.rgb }.ifEmpty { v.glows.map { it.rgb } }),
            CcOverviewText.mixRgb(v.glows.map { it.rgb }.ifEmpty { old.glows.map { it.rgb } }))
      }
      if (lights.isEmpty() && washes.isEmpty()) return null
      val ordered = lights.sortedWith(compareBy({ it.roomId }, { it.x + it.y })).mapIndexed { i, l -> l.copy(order = minOf(i, MAX_STAGGERED - 1)) }
      return CcLightFade(ordered, washes)
    }

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)
  }
}
