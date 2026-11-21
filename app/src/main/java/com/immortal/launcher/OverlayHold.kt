/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/**
 * One critical overlay (a real Control Center alert, a doorbell card) that must outlive its window
 * host. The overlays are `TYPE_ACCESSIBILITY_OVERLAY` windows of [BarWatchService], and Android 10
 * unbinds every accessibility service whenever a UiAutomation client runs (`uiautomator dump`,
 * capture tooling), on `adb install -r`, and on some system rebinds. The system then removes the
 * windows. The alert itself is still current, so the hold keeps it: [hostLost] parks it, [hostReady]
 * hands it back with its own deadline (the time it was unbound counts), and a dismissal, expiry or
 * newer alert while it is parked applies as if it were showing. Pure and immutable; owners keep the
 * value on the main thread.
 */
internal data class OverlayHold<T>(val held: Held<T>? = null, val hostUp: Boolean = false) {
  data class Held<T>(val id: String, val payload: T, val deadlineMs: Long)

  /** On screen now (held and the host is up). */
  val shown: Boolean get() = held != null && hostUp

  /** Parked: held while the host is away, waiting for [hostReady]. */
  val pending: Boolean get() = held != null && !hostUp

  /** [id] replaces whatever is held, showing or parked. Admission (severity order) is the caller's rule. */
  fun offer(id: String, payload: T, deadlineMs: Long): OverlayHold<T> = copy(held = Held(id, payload, deadlineMs))

  /** Dismissed (by the user, or by another room). [id] null: whatever is held; a different id: unchanged. */
  fun dismiss(id: String? = null): OverlayHold<T> = if (held != null && (id == null || id == held.id)) copy(held = null) else this

  /** Its time is up, shown or parked. */
  fun expire(nowMs: Long): OverlayHold<T> = if (held != null && nowMs >= held.deadlineMs) copy(held = null) else this

  /** The host went away: the window is gone, the alert stays. */
  fun hostLost(): OverlayHold<T> = copy(hostUp = false)

  /** The host is back: the held alert (if still in time) is shown again; see [remainingMs]. */
  fun hostReady(nowMs: Long): OverlayHold<T> = copy(hostUp = true).expire(nowMs)

  /** What is left of the held alert's time, or null when nothing is held or it is over. */
  fun remainingMs(nowMs: Long): Long? = held?.let { h -> (h.deadlineMs - nowMs).takeIf { it > 0 } }
}

/**
 * The on-disk form of a held overlay, so an app update (`adb install -r` kills the process) doesn't
 * drop a takeover that was up: the restarted process restores it and re-shows it when the host binds.
 * Deadlines are `elapsedRealtime`, so a record from an earlier boot is refused (boot count, and an
 * uptime that went backwards). The payload is the owner's own JSON, validated by the owner on decode.
 */
internal object OverlayHoldRecord {
  /** `Settings.Global.BOOT_COUNT` couldn't be read. Two unknowns never match, so such a record is never restored. */
  const val UNKNOWN_BOOT = -1
  private const val MAX_ID = 200

  data class Restored(val id: String, val payload: JSONObject, val deadlineMs: Long)

  fun encode(held: OverlayHold.Held<JSONObject>, nowMs: Long, bootCount: Int): JSONObject = JSONObject()
      .put("id", held.id).put("payload", held.payload).put("deadline", held.deadlineMs).put("saved", nowMs).put("boot", bootCount)

  fun decode(o: JSONObject?, nowMs: Long, bootCount: Int): Restored? {
    if (o == null || bootCount == UNKNOWN_BOOT || o.optInt("boot", UNKNOWN_BOOT) != bootCount) return null
    if (nowMs < o.optLong("saved", Long.MAX_VALUE)) return null // uptime went backwards: another boot
    val id = o.optString("id").takeIf { it.isNotBlank() && it.length <= MAX_ID } ?: return null
    val payload = o.optJSONObject("payload") ?: return null
    val deadline = o.optLong("deadline", 0L).takeIf { it > nowMs } ?: return null
    return Restored(id, payload, deadline)
  }
}
