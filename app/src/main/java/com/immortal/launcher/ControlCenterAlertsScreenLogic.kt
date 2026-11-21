/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar
import java.util.TimeZone

/** The Alerts screen's filter (`Active · N` / `All` / `Resolved`). */
internal enum class CcAlertFilter { ACTIVE, ALL, RESOLVED }

/** An alert that went away this session: dismissed here, or resolved in the house. [icon] is its card's icon while active. */
@Immutable
internal data class CcResolvedAlert(val alert: CcAlert, val at: Long, val icon: String? = null)

/**
 * The Alerts screen's slice of a snapshot: the active alerts' cards, derived once per snapshot on
 * the engine thread ([CcUiState.alertCards]), and the start of today (resolved cards' stamps).
 * Equal whenever nothing a card shows changed, so the screen and its cards skip on HA updates.
 */
@Immutable
internal data class CcAlertCards(val active: List<CcAlertItem>, val dayStart: Long)

/** What an alert card's button does. */
@Immutable
internal sealed interface CcAlertOp {
  data class Act(val entity: CcEntity, val call: CcCall) : CcAlertOp
  data class Dismiss(val until: Long) : CcAlertOp
  data class OpenRoom(val roomId: String) : CcAlertOp
}

/** One alert card, fully derived (the card composable only draws it). */
@Immutable
internal data class CcAlertItem(
    val alert: CcAlert,
    val active: Boolean,
    val icon: String,
    val line: String,
    val label: String?,
    val danger: Boolean,
    val enabled: Boolean,
    val op: CcAlertOp?,
    /** The button's screen-reader name, "Office door is open · Remind 10 pm" (numbered by [CcAlertRules.named]). */
    val spoken: String? = label?.let { CcSpeech.alertAction(alert.title, it) },
)

/** Pure Alerts screen rules (unit tested): snooze/remind times, button labels, the water valve. */
internal object CcAlertRules {
  const val SNOOZE_MS = 24 * 60 * 60 * 1000L
  const val REMIND_FALLBACK_MS = 60 * 60 * 1000L
  const val REMIND_HOUR = 22
  /** Only offer "tonight at 10" when it is at least this far away. */
  const val REMIND_MIN_LEAD_MS = 60 * 60 * 1000L
  const val RESOLVED_MAX = 30

  /** Until when tapping the alert's button hides it; null = the button acts on the house instead. */
  fun until(action: CcAlertAction, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? = when (action) {
    CcAlertAction.SNOOZE -> now + SNOOZE_MS
    CcAlertAction.REMIND -> remindAt(now, zone)
    CcAlertAction.DISMISS -> Long.MAX_VALUE
    CcAlertAction.CLOSE, CcAlertAction.SHUT_WATER, CcAlertAction.VIEW -> null
  }

  /** 10 pm tonight when that is an hour or more away, else an hour from now. */
  fun remindAt(now: Long, zone: TimeZone = TimeZone.getDefault()): Long {
    val tonight = Calendar.getInstance(zone).apply {
      timeInMillis = now; set(Calendar.HOUR_OF_DAY, REMIND_HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return if (tonight - now >= REMIND_MIN_LEAD_MS) tonight else now + REMIND_FALLBACK_MS
  }

  fun label(action: CcAlertAction, now: Long, zone: TimeZone = TimeZone.getDefault()): String = when (action) {
    CcAlertAction.CLOSE -> "Close"
    CcAlertAction.SHUT_WATER -> "Shut off water"
    CcAlertAction.VIEW -> "View"
    CcAlertAction.DISMISS -> "Dismiss"
    CcAlertAction.SNOOZE -> "Snooze"
    CcAlertAction.REMIND -> if (remindAt(now, zone) - now == REMIND_FALLBACK_MS) "Remind in 1 h" else "Remind 10 pm"
  }

  /** The valve to close for a leak: same room first, then any valve (or valve-like switch). */
  fun valveFor(alert: CcAlert, entities: Collection<CcEntity>, roomOf: (String) -> String?): CcEntity? {
    val valves = CcSecurity.valves(entities).filter { !it.unavailable }
    val room = roomOf(alert.entityId)
    return valves.firstOrNull { room != null && roomOf(it.id) == room } ?: valves.minByOrNull { if (it.domain == "valve") 0 else 1 }
  }

  /** The garage door to close: the alert's own cover, else the house's garage cover (for a contact sensor). */
  fun garageFor(alert: CcAlert, entities: Map<String, CcEntity>): CcEntity? =
      entities[alert.entityId]?.takeIf { it.domain == "cover" }
          ?: entities.values.filter { it.domain == "cover" && CcKind.of(it) == CcKind.GARAGE }.minByOrNull { it.id }

  /** The call that shuts [valve]: `valve.close_valve`, or turning a valve switch off. */
  fun shutCall(valve: CcEntity): CcCall = if (valve.domain == "valve") CcCalls.valveClose(valve) else CcCalls.power(valve, false)

  fun tone(severity: CcSeverity): CcTone = when (severity) {
    CcSeverity.CRITICAL -> CcTone.RED
    CcSeverity.WARNING -> CcTone.AMBER
    CcSeverity.INFO -> CcTone.NEUTRAL
  }

  fun icon(alert: CcAlert, entity: CcEntity?): String = when (alert.kind) {
    CcKind.APPLIANCE -> applianceIcon(alert.entityId)
    CcKind.SMOKE -> "warn"
    else -> CcIcons.forKind(entity?.let(CcKind::of) ?: alert.kind)
  }

  private fun applianceIcon(entityId: String): String = when {
    "dish" in entityId -> "dish"
    "dryer" in entityId -> "dryer"
    "oven" in entityId || "stove" in entityId || "cook" in entityId -> "oven"
    else -> "washer"
  }

  /**
   * Updates the session's resolved list: alerts [seen] before that are no longer [active] are
   * resolved now (newest first, capped). [dismissed] alerts are added by the screen directly.
   */
  fun resolve(resolved: List<CcResolvedAlert>, seen: Collection<CcAlert>, active: List<CcAlert>, now: Long,
              iconOf: (CcAlert) -> String? = { null }): List<CcResolvedAlert> {
    val activeIds = active.map { it.id }.toSet()
    val known = resolved.map { it.alert.id }.toSet()
    val gone = seen.filter { it.id !in activeIds && it.id !in known }.map { CcResolvedAlert(it, now, iconOf(it)) }
    return (gone + resolved).take(RESOLVED_MAX)
  }

  /** Derives a card: icon, "body · time", and the one button (or none when it can't act). */
  fun item(alert: CcAlert, active: Boolean, entities: Map<String, CcEntity>, roomOf: (String) -> String?, canControl: Boolean,
           now: Long, time: (Long) -> String, zone: TimeZone = TimeZone.getDefault(),
           day: (Long) -> String = { dayLabel(it, zone) }): CcAlertItem {
    val line = line(alert, now, time, day, zone)
    val icon = icon(alert, entities[alert.entityId])
    val op: CcAlertOp? = if (!active) null else when (alert.action) {
      CcAlertAction.CLOSE -> garageFor(alert, entities)?.takeIf { it.isOpen }?.let { CcAlertOp.Act(it, CcCalls.coverClose(it)) }
      CcAlertAction.SHUT_WATER -> valveFor(alert, entities.values, roomOf)?.takeIf { it.isOpen || it.isOn }?.let { CcAlertOp.Act(it, shutCall(it)) }
      CcAlertAction.VIEW -> roomOf(alert.entityId)?.let { CcAlertOp.OpenRoom(it) }
      CcAlertAction.DISMISS, CcAlertAction.SNOOZE, CcAlertAction.REMIND -> until(alert.action, now, zone)?.let { CcAlertOp.Dismiss(it) }
    }
    val enabled = op !is CcAlertOp.Act || canControl
    return CcAlertItem(alert, active, icon, line, op?.let { label(alert.action, now, zone) }, alert.severity == CcSeverity.CRITICAL, enabled, op)
  }

  /** "body · time" under the card's title. */
  private fun line(alert: CcAlert, now: Long, time: (Long) -> String, day: (Long) -> String, zone: TimeZone): String =
      listOfNotNull(alert.body, alert.since.takeIf { it > 0 }?.let { stamp(it, now, time, day, zone) }).joinToString(" · ")

  /** The active alerts' cards for one snapshot (the engine derives them off the main thread). */
  fun cards(active: List<CcAlert>, entities: Map<String, CcEntity>, roomOf: (String) -> String?, canControl: Boolean,
            now: Long, time: (Long) -> String, zone: TimeZone = TimeZone.getDefault()): CcAlertCards =
      CcAlertCards(active.map { item(it, true, entities, roomOf, canControl, now, time, zone) }, dayStart(now, zone))

  /** A resolved alert's card: no button, the icon it had while active, stamped against [dayStart] (today). */
  fun resolvedItem(r: CcResolvedAlert, dayStart: Long, time: (Long) -> String, zone: TimeZone = TimeZone.getDefault(),
                   day: (Long) -> String = { dayLabel(it, zone) }): CcAlertItem {
    val a = r.alert
    return CcAlertItem(a, false, r.icon ?: icon(a, null), line(a, dayStart, time, day, zone), null, a.severity == CcSeverity.CRITICAL, true, null)
  }

  /** The cards [filter] shows: active first, then resolved ones that aren't active again; repeated buttons numbered. */
  fun shown(filter: CcAlertFilter, cards: CcAlertCards, resolved: List<CcResolvedAlert>, time: (Long) -> String,
            zone: TimeZone = TimeZone.getDefault(), day: (Long) -> String = { dayLabel(it, zone) }): List<CcAlertItem> {
    val done = if (filter == CcAlertFilter.ACTIVE) emptyList() else {
      val activeIds = cards.active.mapTo(HashSet()) { it.alert.id }
      resolved.filter { it.alert.id !in activeIds }.map { resolvedItem(it, cards.dayStart, time, zone, day) }
    }
    return named(when (filter) {
      CcAlertFilter.ACTIVE -> cards.active
      CcAlertFilter.RESOLVED -> done
      CcAlertFilter.ALL -> cards.active + done
    })
  }

  /** Local midnight of [now]'s day. */
  fun dayStart(now: Long, zone: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(zone).apply {
    timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
  }.timeInMillis

  /** Numbers buttons that would read the same ("…, 2 of 3"): one sensor can raise several identical alerts. */
  fun named(items: List<CcAlertItem>): List<CcAlertItem> {
    val repeats = CcSpeech.repeatSuffixes(items.map { it.spoken.orEmpty() })
    return items.mapIndexed { i, item -> if (item.spoken == null || repeats[i].isEmpty()) item else item.copy(spoken = item.spoken + repeats[i]) }
  }

  /** "2:33 pm" today; "Sat 7 Nov 2:33 pm" on any other day, so an old alert's time isn't read as today's. */
  fun stamp(since: Long, now: Long, time: (Long) -> String, day: (Long) -> String, zone: TimeZone): String =
      if (sameDay(since, now, zone)) time(since) else "${day(since)} ${time(since)}"

  private fun sameDay(a: Long, b: Long, zone: TimeZone): Boolean {
    val ca = Calendar.getInstance(zone).apply { timeInMillis = a }
    val cb = Calendar.getInstance(zone).apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
  }

  private fun dayLabel(ms: Long, zone: TimeZone): String =
      CcDateFormats.format("EEE d MMM", ms, zone)
}

/** This session's resolved alerts (process lifetime; replaced, never mutated in place). */
internal object CcAlertHistory {
  private val state = MutableStateFlow<List<CcResolvedAlert>>(emptyList())
  private val seen = HashMap<String, CcAlertItem>()
  val resolved: StateFlow<List<CcResolvedAlert>> get() = state

  @Synchronized fun observe(active: List<CcAlertItem>, now: Long) {
    state.value = CcAlertRules.resolve(state.value, seen.values.map { it.alert }, active.map { it.alert }, now) { seen[it.id]?.icon }
    seen.clear()
    active.forEach { seen[it.alert.id] = it }
  }

  @Synchronized fun dismissed(item: CcAlertItem, now: Long) {
    val alert = item.alert
    if (state.value.none { it.alert.id == alert.id }) {
      state.value = (listOf(CcResolvedAlert(alert, now, item.icon)) + state.value).take(CcAlertRules.RESOLVED_MAX)
    }
  }
}
