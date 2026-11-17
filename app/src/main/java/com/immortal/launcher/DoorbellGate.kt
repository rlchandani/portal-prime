/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Decides whether an alert is shown here. Immutable: [admit] returns the next gate.
 * - Dedupe: an alert id seen before (this Portal's own listener and the household copy of the
 *   same press, or a second listening Portal) is shown once.
 * - Cooldown per camera: within the cooldown nothing new shows for that camera, except that a
 *   doorbell press after motion still does (it matters more).
 */
internal data class DoorbellGate(
    val seen: Map<String, Long> = emptyMap(),
    val lastShown: Map<String, Shown> = emptyMap(),
) {
  data class Shown(val atMs: Long, val kind: DoorbellKind)

  data class Result(val gate: DoorbellGate, val show: Boolean, val reason: Reason)

  enum class Reason { SHOWN, DUPLICATE, COOLDOWN }

  fun admit(alert: DoorbellAlert, nowMs: Long, cooldownMs: Long): Result {
    val pruned = prune(nowMs)
    if (alert.id in pruned.seen) return Result(pruned, show = false, reason = Reason.DUPLICATE)
    val marked = pruned.copy(seen = pruned.seen + (alert.id to nowMs))
    val last = marked.lastShown[alert.cooldownKey]
    val cooling = last != null && nowMs - last.atMs in 0 until cooldownMs
    val upgrade = alert.kind == DoorbellKind.RING && last?.kind == DoorbellKind.MOTION
    if (cooling && !upgrade) return Result(marked, show = false, reason = Reason.COOLDOWN)
    val shown = marked.copy(lastShown = marked.lastShown + (alert.cooldownKey to Shown(nowMs, alert.kind)))
    return Result(shown, show = true, reason = Reason.SHOWN)
  }

  private fun prune(nowMs: Long): DoorbellGate {
    val fresh = seen.filterValues { nowMs - it in 0 until SEEN_TTL_MS }
    val capped = if (fresh.size <= MAX_SEEN) fresh
        else fresh.entries.sortedByDescending { it.value }.take(MAX_SEEN).associate { it.key to it.value }
    return copy(seen = capped, lastShown = lastShown.filterValues { nowMs - it.atMs in 0 until SEEN_TTL_MS })
  }

  companion object {
    /** Long enough for every repeat and relay of one press; far longer than any cooldown. */
    const val SEEN_TTL_MS = 15 * 60_000L
    const val MAX_SEEN = 64
  }
}

/** Sound and copy for an alert in this room. Pure. */
internal object DoorbellRing {
  /** The card closes by itself after this long. */
  const val SHOW_MS = 60_000L

  data class Decision(val sound: Boolean, val quiet: Boolean)

  /** Quiet hours (the chime's window, shared with timers and Call everyone) are always visual only. */
  fun decide(chimeOn: Boolean, quietHours: Boolean): Decision =
      Decision(sound = chimeOn && !quietHours, quiet = quietHours)

  fun title(alert: DoorbellAlert): String = when (alert.kind) {
    DoorbellKind.RING -> "Someone’s at the door"
    DoorbellKind.MOTION -> "Motion at ${alert.cameraName}"
  }

  fun statusLine(decision: Decision, chimeOn: Boolean): String = when {
    decision.quiet -> "Quiet hours, shown without sound"
    !chimeOn -> "Chime is off on this Portal"
    else -> "Shown in every room"
  }
}
