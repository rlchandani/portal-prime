/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/**
 * Which Control Center alerts reach the screen while Hearthpane runs in the background (pure, unit
 * tested). Every Home Assistant entity takes part, by its device class, not only the floor plan's:
 *  - leak (moisture), smoke, CO and gas take over every Portal ([CcBannerPolicy] keeps gas silent at night);
 *  - doors, windows and the garage left open are one consolidated warning banner, at most once per
 *    [OPEN_BANNER_INTERVAL_MS], on shared-room Portals only;
 *  - appliance-done info keeps its existing banner; motion, tamper, `safety`, battery and the rest never banner.
 */
internal object CcBackgroundPolicy {
  /** Device classes that take over the screen. */
  val CRITICAL_CLASSES = setOf("moisture", "smoke", "carbon_monoxide", "gas")
  /** Binary-sensor and cover classes that warn when left open. */
  val OPEN_CLASSES = CcEntity.OPENING_CLASSES + setOf("garage", "gate")
  private val OPEN_KINDS = setOf(CcKind.DOOR, CcKind.WINDOW, CcKind.GARAGE)

  /** One takeover per entity in this window, however often a flapping sensor re-triggers. */
  const val CRITICAL_COOLDOWN_MS = 5 * 60_000L
  /** "N doors open" shows at most this often on one Portal (survives restarts). */
  const val OPEN_BANNER_INTERVAL_MS = 60 * 60_000L

  enum class Route { TAKEOVER, OPEN_WARNING, INFO, NONE }

  fun route(a: CcAlert): Route = when {
    a.severity == CcSeverity.CRITICAL && (a.kind == CcKind.LEAK || a.deviceClass in CRITICAL_CLASSES) -> Route.TAKEOVER
    a.severity == CcSeverity.WARNING && a.kind in OPEN_KINDS -> Route.OPEN_WARNING
    a.severity == CcSeverity.INFO && a.kind == CcKind.APPLIANCE -> Route.INFO
    else -> Route.NONE
  }

  /** Classes worth a background subscription (the alert set above, nothing else). */
  fun subscribes(deviceClass: String?): Boolean = deviceClass in CRITICAL_CLASSES || deviceClass in OPEN_CLASSES

  /** What one pass over the active alerts presents. */
  data class Plan(val takeovers: List<CcAlert>, val openFresh: List<CcAlert>, val openActive: List<CcAlert>, val info: List<CcAlert>)

  /**
   * [active] is every alert now; [seen] the ids already seen (null on the first pass after start).
   * At start only criticals and doors already open past the warning delay count (an alert is only
   * active once it has matured); info never replays.
   */
  fun plan(active: List<CcAlert>, seen: Set<String>?): Plan {
    val fresh = active.filter { seen == null || it.id !in seen }
    val openActive = active.filter { route(it) == Route.OPEN_WARNING }
    return Plan(
        takeovers = fresh.filter { route(it) == Route.TAKEOVER },
        openFresh = fresh.filter { route(it) == Route.OPEN_WARNING },
        openActive = openActive,
        info = if (seen == null) emptyList() else fresh.filter { route(it) == Route.INFO },
    )
  }

  /** One banner for everything left open: the alert itself when it's alone, else "N doors open". */
  fun consolidate(open: List<CcAlert>, now: Long): CcAlert? {
    if (open.isEmpty()) return null
    val sorted = open.sortedBy { it.since }
    if (sorted.size == 1) return sorted.single()
    val n = sorted.size
    val title = when {
      sorted.all { it.kind == CcKind.WINDOW } -> "$n windows open"
      sorted.none { it.kind == CcKind.WINDOW } -> "$n doors open"
      else -> "$n doors and windows open"
    }
    val names = sorted.map { it.title.removeSuffix(" is open") }
    val body = if (n <= 2) names.joinToString(" and ") else "${names.take(2).joinToString(", ")} and ${n - 2} more"
    val kind = if (sorted.all { it.kind == CcKind.GARAGE }) CcKind.GARAGE else CcKind.DOOR
    return CcAlert("open:$n@$now", CcSeverity.WARNING, kind, sorted.first().entityId, title, body, sorted.first().since,
        CcAlertAction.REMIND)
  }
}

/**
 * Admission for background alerts on this Portal. Immutable: each call returns the next gate.
 * Ids are `entity@last_changed`, identical on every Portal watching the same Home Assistant, so a
 * takeover seen from this Portal's socket and from the household relay shows once.
 */
internal data class CcAlertGate(
    val seen: Map<String, Long> = emptyMap(),
    val takeoverAt: Map<String, Long> = emptyMap(),
    val openBannerAt: Long = 0L,
) {
  enum class Reason { SHOWN, DUPLICATE, COOLDOWN }

  data class Result(val gate: CcAlertGate, val show: Boolean, val reason: Reason)

  fun admitTakeover(alert: CcAlert, now: Long): Result {
    val pruned = prune(now)
    if (alert.id in pruned.seen) return Result(pruned, false, Reason.DUPLICATE)
    val marked = pruned.copy(seen = pruned.seen + (alert.id to now))
    val last = marked.takeoverAt[alert.entityId]
    if (last != null && now - last in 0 until CcBackgroundPolicy.CRITICAL_COOLDOWN_MS) return Result(marked, false, Reason.COOLDOWN)
    return Result(marked.copy(takeoverAt = marked.takeoverAt + (alert.entityId to now)), true, Reason.SHOWN)
  }

  /** The consolidated open banner: once per interval (a clock set backwards doesn't block it forever). */
  fun admitOpen(now: Long): Result {
    val since = now - openBannerAt
    if (openBannerAt > 0 && since in 0 until CcBackgroundPolicy.OPEN_BANNER_INTERVAL_MS) return Result(this, false, Reason.COOLDOWN)
    return Result(copy(openBannerAt = now), true, Reason.SHOWN)
  }

  private fun prune(now: Long): CcAlertGate {
    val fresh = seen.filterValues { now - it in 0 until SEEN_TTL_MS }
    val capped = if (fresh.size <= MAX_SEEN) fresh else fresh.entries.sortedByDescending { it.value }.take(MAX_SEEN).associate { it.key to it.value }
    return copy(seen = capped, takeoverAt = takeoverAt.filterValues { now - it in 0 until SEEN_TTL_MS })
  }

  companion object {
    /** Longer than every relay repeat and the takeover cooldown. */
    const val SEEN_TTL_MS = 30 * 60_000L
    const val MAX_SEEN = 64
  }
}

/** `cc_alert` household message: a takeover seen by one Portal, shown on the others (validated on receipt). */
internal object CcAlertWire {
  const val TYPE = "cc_alert"
  private const val MAX_TEXT = 160
  private const val MAX_ID = 200
  private val KINDS = setOf(CcKind.LEAK, CcKind.SMOKE)

  fun encode(a: CcAlert): JSONObject = JSONObject()
      .put("id", a.id.take(MAX_ID)).put("kind", a.kind.name).put("entity", a.entityId.take(MAX_ID))
      .put("title", a.title.take(MAX_TEXT)).put("body", a.body.take(MAX_TEXT)).put("since", a.since)
      .put("cls", a.deviceClass ?: JSONObject.NULL)

  fun decode(o: JSONObject?): CcAlert? {
    if (o == null) return null
    val id = o.optString("id").takeIf { it.isNotBlank() && it.length <= MAX_ID } ?: return null
    val kind = runCatching { CcKind.valueOf(o.optString("kind")) }.getOrNull()?.takeIf { it in KINDS } ?: return null
    val entity = o.optString("entity").takeIf { it.isNotBlank() && it.length <= MAX_ID } ?: return null
    val cls = if (o.isNull("cls")) null else o.optString("cls").takeIf { it in CcBackgroundPolicy.CRITICAL_CLASSES } ?: return null
    if (kind == CcKind.SMOKE && cls == null) return null
    val title = o.optString("title").take(MAX_TEXT).ifBlank { return null }
    val action = if (kind == CcKind.LEAK) CcAlertAction.SHUT_WATER else CcAlertAction.VIEW
    return CcAlert(id, CcSeverity.CRITICAL, kind, entity, title, o.optString("body").take(MAX_TEXT), o.optLong("since"), action, cls)
  }
}
