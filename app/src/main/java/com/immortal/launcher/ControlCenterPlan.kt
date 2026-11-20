/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * The household floor plan. Home Assistant has no room geometry, so the plan is ours: per floor,
 * room boxes on a snap grid (one per HA area), openings (door/window/garage sensors on a wall) and
 * device positions inside a room. Stored as JSON in HA's frontend user data (shared by every
 * Portal on the same HA user) and cached on each Portal ([CcPlanStore]). Pure + immutable.
 */
@Immutable
internal data class CcPlan(val floors: List<CcFloor>, val updatedAt: Long = 0L) {
  val isEmpty: Boolean get() = floors.all { it.rooms.isEmpty() }
  fun floor(id: String?): CcFloor? = floors.firstOrNull { it.id == id } ?: floors.firstOrNull()
  fun roomOf(entityId: String): CcRoom? = roomByEntity[entityId]
  /** Device → its (first) room, built once per plan: alerts ask for every entity on every HA update. */
  private val roomByEntity: Map<String, CcRoom> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    HashMap<String, CcRoom>().also { m -> floors.forEach { f -> f.rooms.forEach { r -> r.devices.forEach { m.putIfAbsent(it.entityId, r) } } } }
  }
  fun room(id: String): CcRoom? = floors.asSequence().flatMap { it.rooms.asSequence() }.firstOrNull { it.id == id }
  /** Every entity the plan shows (rooms + openings) — the live subscription's core set. */
  val entityIds: Set<String>
    get() = floors.flatMap { f -> f.rooms.flatMap { r -> r.devices.map { it.entityId } } + f.openings.map { it.entityId } }.toSet()

  companion object {
    const val VERSION = 1
    /** Snap-grid size of every floor: 48 × 30 cells keeps a 3:2 Portal-shaped canvas. */
    const val GRID_W = 48
    const val GRID_H = 30
    val EMPTY = CcPlan(emptyList())
  }
}

@Immutable
internal data class CcFloor(
    val id: String,
    val name: String,
    val rooms: List<CcRoom> = emptyList(),
    val openings: List<CcOpening> = emptyList(),
)

@Immutable
internal data class CcRoom(
    /** The HA area id (rooms without an area use a local "room_<n>" id). */
    val id: String,
    val name: String,
    val rect: CcRect,
    /** Driveway, patio, garden: drawn as a softer outdoor slab. */
    val outdoor: Boolean = false,
    val devices: List<CcPlaced> = emptyList(),
)

/** Grid cells; always at least 1×1 and inside the floor. */
@Immutable
internal data class CcRect(val x: Int, val y: Int, val w: Int, val h: Int) {
  val right: Int get() = x + w
  val bottom: Int get() = y + h
  fun contains(px: Float, py: Float): Boolean = px >= x && px <= right && py >= y && py <= bottom
  fun overlaps(o: CcRect): Boolean = x < o.right && o.x < right && y < o.bottom && o.y < bottom

  fun clamped(gridW: Int = CcPlan.GRID_W, gridH: Int = CcPlan.GRID_H): CcRect {
    val cw = w.coerceIn(1, gridW)
    val ch = h.coerceIn(1, gridH)
    return CcRect(x.coerceIn(0, gridW - cw), y.coerceIn(0, gridH - ch), cw, ch)
  }
}

/** A device inside a room at a fractional position (0..1 on each axis of the room box). */
@Immutable
internal data class CcPlaced(val entityId: String, val fx: Float, val fy: Float)

internal enum class CcSide { N, E, S, W }

/** A door/window/garage opening sitting on one wall of [roomId]; [at] and [span] are 0..1 of that wall. */
@Immutable
internal data class CcOpening(val entityId: String, val roomId: String, val side: CcSide, val at: Float, val span: Float)

/** JSON round trip for the plan; tolerant on read (bad rooms dropped, values clamped). */
internal object CcPlanJson {
  /** HA frontend user data key shared by every Portal on the HA user. */
  const val HA_KEY = "hearthpane_floorplan"
  /** Our own ceiling: HA's websocket accepts far more, but a plan this big is a bug. */
  const val MAX_BYTES = 256 * 1024

  fun encode(plan: CcPlan): JSONObject = JSONObject()
      .put("v", CcPlan.VERSION)
      .put("updatedAt", plan.updatedAt)
      .put("floors", JSONArray(plan.floors.map(::floor)))

  private fun floor(f: CcFloor) = JSONObject().put("id", f.id).put("name", f.name)
      .put("rooms", JSONArray(f.rooms.map(::room)))
      .put("openings", JSONArray(f.openings.map { o ->
        JSONObject().put("e", o.entityId).put("room", o.roomId).put("side", o.side.name).put("at", o.at.toDouble()).put("span", o.span.toDouble())
      }))

  private fun room(r: CcRoom) = JSONObject().put("id", r.id).put("name", r.name)
      .put("r", JSONArray(listOf(r.rect.x, r.rect.y, r.rect.w, r.rect.h))).put("out", r.outdoor)
      .put("d", JSONArray(r.devices.map { d -> JSONObject().put("e", d.entityId).put("x", d.fx.toDouble()).put("y", d.fy.toDouble()) }))

  fun decode(o: JSONObject?): CcPlan? {
    if (o == null || o.optInt("v", 0) != CcPlan.VERSION) return null
    val floors = o.optJSONArray("floors") ?: return null
    return CcPlan((0 until floors.length()).mapNotNull { floors.optJSONObject(it)?.let(::decodeFloor) },
        o.optLong("updatedAt", 0L))
  }

  fun decodeString(s: String?): CcPlan? = s?.let { runCatching { decode(JSONObject(it)) }.getOrNull() }

  private fun decodeFloor(o: JSONObject): CcFloor? {
    val id = o.optString("id").ifBlank { return null }
    val rooms = o.optJSONArray("rooms")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::decodeRoom) } }.orEmpty()
    val roomIds = rooms.map { it.id }.toSet()
    val openings = o.optJSONArray("openings")?.let { a ->
      (0 until a.length()).mapNotNull { i ->
        val x = a.optJSONObject(i) ?: return@mapNotNull null
        val side = runCatching { CcSide.valueOf(x.optString("side")) }.getOrNull() ?: return@mapNotNull null
        val e = x.optString("e").takeIf { it.contains('.') } ?: return@mapNotNull null
        val room = x.optString("room").takeIf { it in roomIds } ?: return@mapNotNull null
        CcOpening(e, room, side, unit(x.optDouble("at", 0.5)), unit(x.optDouble("span", OPENING_SPAN)).coerceAtLeast(MIN_SPAN))
      }
    }.orEmpty()
    return CcFloor(id, o.optString("name").ifBlank { id }, rooms, openings)
  }

  private fun decodeRoom(o: JSONObject): CcRoom? {
    val id = o.optString("id").ifBlank { return null }
    val r = o.optJSONArray("r")?.takeIf { it.length() == RECT_PARTS } ?: return null
    val rect = CcRect(r.optInt(0), r.optInt(1), r.optInt(2), r.optInt(3)).clamped()
    val devices = o.optJSONArray("d")?.let { a ->
      (0 until a.length()).mapNotNull { i ->
        val d = a.optJSONObject(i) ?: return@mapNotNull null
        val e = d.optString("e").takeIf { it.contains('.') } ?: return@mapNotNull null
        CcPlaced(e, unit(d.optDouble("x", 0.5)), unit(d.optDouble("y", 0.5)))
      }
    }.orEmpty()
    return CcRoom(id, o.optString("name").ifBlank { id }, rect, o.optBoolean("out", false), devices)
  }

  private fun unit(v: Double): Float = (if (v.isNaN()) 0.5 else v).toFloat().coerceIn(0f, 1f)

  /** UTF-8 size of the encoded plan, as Home Assistant's user-data store would hold it. */
  fun encodedBytes(plan: CcPlan): Int = encode(plan).toString().toByteArray(Charsets.UTF_8).size

  /** Fits HA's user-data store? */
  fun fits(plan: CcPlan): Boolean = encodedBytes(plan) <= MAX_BYTES

  /** Whole kilobytes, rounded up, for "312 KB, limit 256 KB". */
  fun kilobytes(bytes: Int): Int = (bytes + KB - 1) / KB

  /** Save toast for a plan of [bytes] that Home Assistant will never hold, connected or not. */
  fun tooLargeToast(bytes: Int): String =
      "Too large to save in Home Assistant · kept on this Portal (${kilobytes(bytes)} KB, limit ${MAX_BYTES / KB} KB). " +
          "Simplify the plan (fewer rooms or devices) to share it."

  /** Settings line under "On this Portal only" for the same plan. */
  fun tooLargeLine(bytes: Int): String =
      "Too large to save in Home Assistant (${kilobytes(bytes)} KB, limit ${MAX_BYTES / KB} KB) · simplify the plan to share it"

  private const val KB = 1024

  private const val RECT_PARTS = 4
  const val OPENING_SPAN = 0.3
  private const val MIN_SPAN = 0.05f
}
