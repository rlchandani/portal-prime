/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * A plan input compared by identity: the engine and the canvas caches keep an unchanged model,
 * geometry or text set as the same object, so "same object" means "nothing to redo".
 */
internal class CcSame(private val v: Any?) {
  override fun equals(other: Any?): Boolean = other is CcSame && other.v === v
  override fun hashCode(): Int = System.identityHashCode(v)
}

/** A least-recently-used cache that hands back what it drops (pure; UI thread only). */
internal class CcEvictingLru<K : Any, V : Any>(private val max: Int, private val onEvict: (V) -> Unit) {
  private val map = LinkedHashMap<K, V>(max + 1, LOAD, true)
  val size: Int get() = map.size

  operator fun get(key: K): V? = map[key]

  fun getOrPut(key: K, make: () -> V): V = map[key] ?: make().also { put(key, it) }

  fun put(key: K, value: V) {
    map.put(key, value)?.takeIf { it !== value }?.let(onEvict)
    while (map.size > max) {
      val eldest = map.entries.iterator().next()
      map.remove(eldest.key)
      onEvict(eldest.value)
    }
  }

  /** Drops everything (each value goes through [onEvict]). */
  fun clear() {
    val all = map.values.toList()
    map.clear()
    all.forEach(onEvict)
  }

  private companion object { const val LOAD = 0.75f }
}

/** Which plan pass a recorded layer holds (see [CcPlanCanvas]). */
internal enum class CcPlanPass {
  FLOORS, UNDER, WALLS,
  /** The openings: flat, and replayed through the projection in 3D. */
  OPENINGS,
  /** 2D markers (with the pulses' still frame while motion is off). */
  MARKS, MARKS_STILL,
  TOP,
  /** 3D ([CcIsoPx]): the slab, the pulses' still frame on the floor, the standing walls, the upright markers and labels. */
  SLAB3D, PULSES_STILL, WALLS3D, MARKS3D, TOP3D,
}

/**
 * One recorded pass: the pass, the identity of the object that draws it and, for the labels only
 * (other rooms' names fade while one is highlighted), the highlighted room.
 */
internal data class CcPassKey(val pass: CcPlanPass, val source: CcSame, val focus: String? = null)

/**
 * The plan's draw passes recorded once per floor (and per model and lens) into retained
 * [GraphicsLayer]s, so a floor switch — or switching back — replays an existing display list
 * instead of recording every fill, glow, wall, badge and label again inside the frame. Display
 * lists only (no pixels are kept per floor), a few hundred KB at most; evicted layers are released.
 */
internal class CcPlanLayers(private val graphics: GraphicsContext, max: Int = MAX_LAYERS) {
  private val cache = CcEvictingLru<CcPassKey, GraphicsLayer>(max) { graphics.releaseGraphicsLayer(it) }
  /** Passes recorded so far (a cache hit records nothing). */
  var recorded = 0
    private set
  val size: Int get() = cache.size

  fun draw(scope: DrawScope, key: CcPassKey, pass: DrawScope.() -> Unit) = with(scope) {
    val target = IntSize(size.width.roundToInt(), size.height.roundToInt())
    if (target.width <= 0 || target.height <= 0) return@with
    var fresh = false
    val layer = cache.getOrPut(key) { fresh = true; graphics.createGraphicsLayer() }
    if (fresh || layer.isReleased || layer.size != target) {
      ccTrace(RECORD_SECTIONS[key.pass.ordinal]) { layer.record(target) { pass() } }
      recorded++
    }
    drawLayer(layer)
  }

  fun release() = cache.clear()

  companion object {
    /**
     * One canvas per floor ([CcFloorParking]): six 2D passes plus the labels of a highlighted room
     * and, once shown in 3D, five more (and those labels), plus room for the ones an HA update just replaced.
     */
    const val MAX_LAYERS = 20
    private val RECORD_SECTIONS = CcPlanPass.entries.map { "cc:record:${it.name}" }
  }
}

/** What a [CcPlanDraw] is built from; equal keys reuse the same draw object (and its recorded passes). */
internal data class CcDrawKey(
    val model: CcSame, val px: CcSame, val paint: CcSame, val c: CcColors, val labels: CcSame, val chips: CcSame,
    val lens: CcLens, val stale: Boolean, val fahrenheit: Boolean, val dp: Float,
    val skipLit: Set<String> = emptySet(),
)

/** What a [CcPlanBase] is built from. */
internal data class CcBaseKey(val px: CcSame, val c: CcColors, val dp: Float)

/**
 * Which floors stay composed (pure, unit tested): every floor shown recently, at most [max], each
 * with the frame it last showed. The order is the order of first visits and never changes while a
 * floor stays (so a switch moves no nodes); the least recently shown floor leaves first.
 */
internal class CcFloorParking<F : Any>(private val max: Int) {
  private val frames = HashMap<String, F>()
  private val lastShown = HashMap<String, Long>()
  private var tick = 0L
  var order: List<String> = emptyList()
    private set

  /** Shows [id] with [frame]; returns the floors to keep composed (same list instance while unchanged). */
  fun show(id: String, frame: F): List<String> {
    frames[id] = frame
    lastShown[id] = ++tick
    if (id !in order) {
      var next = order + id
      while (next.size > max) {
        val drop = next.filter { it != id }.minBy { lastShown[it] ?: 0L }
        next = next - drop
        frames.remove(drop); lastShown.remove(drop)
      }
      order = next
    }
    return order
  }

  fun frame(id: String): F? = frames[id]
}
