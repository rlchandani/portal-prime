/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Control Center opens fully drawn (owner decision, 2026-11-21). While no screen looks, the background
 * subscription watches only alert entities; the plan's other entities are kept here, in memory, as their
 * last states instead of being dropped. An open puts them back at once, marked unconfirmed, and the
 * foreground subscription's first full answer confirms them in place (unchanged values don't move).
 *
 * Unconfirmed entities never raise alerts. A safety-critical one ([critical]: locks, the alarm, garage
 * doors and gates, leak/smoke/CO/gas sensors) shows the in-flight look and refuses actions until HA has
 * confirmed it, so no action is ever decided from a last-known state. Pure; the engine owns the state.
 */
internal object CcLastKnown {
  /** At most this many entities are retained (the foreground interest is capped the same way). */
  const val MAX_RETAINED = CcInterest.MAX_FOREGROUND

  private val CRITICAL_DOMAINS = setOf("lock", "alarm_control_panel")
  private val CRITICAL_COVER_CLASSES = setOf("garage", "gate")

  fun critical(e: CcEntity): Boolean = when (e.domain) {
    in CRITICAL_DOMAINS -> true
    "cover" -> e.deviceClass in CRITICAL_COVER_CLASSES
    "binary_sensor" -> e.deviceClass in CcBackgroundPolicy.CRITICAL_CLASSES
    else -> false
  }

  /** Going to the background: [keep] (null = everything) stays live, the rest is retained as last known. */
  data class Split(val kept: List<CcEntity>, val retained: Map<String, CcEntity>)

  fun split(raw: Map<String, CcEntity>, keep: Set<String>?, previous: Map<String, CcEntity>): Split {
    if (keep == null) return Split(raw.values.toList(), emptyMap())
    val (kept, dropped) = raw.values.partition { it.id in keep }
    val retained = previous.filterKeys { it !in keep } + dropped.associateBy { it.id }
    return Split(kept, cap(retained))
  }

  /** Newest entries win when over [MAX_RETAINED] (insertion order: the latest drop is last). */
  private fun cap(retained: Map<String, CcEntity>): Map<String, CcEntity> =
      if (retained.size <= MAX_RETAINED) retained
      else retained.entries.toList().takeLast(MAX_RETAINED).associate { it.key to it.value }

  /** An open: the retained entities HA hasn't already given back. They are unconfirmed until HA answers. */
  fun restorable(raw: Map<String, CcEntity>, retained: Map<String, CcEntity>): Map<String, CcEntity> =
      retained.filterKeys { it !in raw }

  /** The ids a compressed `subscribe_entities` event gives whole (its `a` block); a diff (`c`) confirms nothing. */
  fun additions(event: org.json.JSONObject): List<String> =
      event.optJSONObject("a")?.keys()?.asSequence()?.toList().orEmpty()

  /** Ids a message gave in full: they're live again. */
  fun confirm(unconfirmed: Set<String>, full: Collection<String>): Set<String> =
      if (unconfirmed.isEmpty() || full.isEmpty()) unconfirmed else unconfirmed - full.toSet()

  /** What the screens show as in flight: real calls, plus safety-critical entities still unconfirmed. */
  fun busy(pending: Set<String>, unconfirmed: Set<String>, entities: Map<String, CcEntity>): Set<String> {
    if (unconfirmed.isEmpty()) return pending
    val checking = unconfirmed.filter { id -> entities[id]?.let(::critical) == true }
    return if (checking.isEmpty()) pending else pending + checking
  }

  /** A call on a safety-critical entity whose state is only last known is refused; HA must confirm it first. */
  fun refusal(entity: CcEntity?, unconfirmed: Set<String>): String? {
    if (entity == null || entity.id !in unconfirmed || !critical(entity)) return null
    return "Checking ${entity.name} — try again in a moment"
  }
}
