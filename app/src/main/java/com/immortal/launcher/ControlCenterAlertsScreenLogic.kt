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

/** An alert that went away this session: dismissed here, or resolved in the house. */
@Immutable
internal data class CcResolvedAlert(val alert: CcAlert, val at: Long)

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
    CcKind.APPLIANCE -> CcEnergyKpis.consumerIcon(alert.entityId).takeIf { it != "bolt" } ?: "washer"
    CcKind.SMOKE -> "warn"
    else -> CcIcons.forKind(entity?.let(CcKind::of) ?: alert.kind)
  }

  /**
   * Updates the session's resolved list: alerts [seen] before that are no longer [active] are
   * resolved now (newest first, capped). [dismissed] alerts are added by the screen directly.
   */
  fun resolve(resolved: List<CcResolvedAlert>, seen: Collection<CcAlert>, active: List<CcAlert>, now: Long): List<CcResolvedAlert> {
    val activeIds = active.map { it.id }.toSet()
    val known = resolved.map { it.alert.id }.toSet()
    val gone = seen.filter { it.id !in activeIds && it.id !in known }.map { CcResolvedAlert(it, now) }
    return (gone + resolved).take(RESOLVED_MAX)
  }

  /** Derives a card: icon, "body · time", and the one button (or none when it can't act). */
  fun item(alert: CcAlert, active: Boolean, entities: Map<String, CcEntity>, roomOf: (String) -> String?, canControl: Boolean,
           now: Long, time: (Long) -> String, zone: TimeZone = TimeZone.getDefault()): CcAlertItem {
    val line = listOfNotNull(alert.body, alert.since.takeIf { it > 0 }?.let(time)).joinToString(" · ")
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

  fun list(filter: CcAlertFilter, active: List<CcAlert>, resolved: List<CcResolvedAlert>): List<Pair<CcAlert, Boolean>> {
    val activeIds = active.map { it.id }.toSet()
    val done = resolved.filter { it.alert.id !in activeIds }.map { it.alert to false }
    return when (filter) {
      CcAlertFilter.ACTIVE -> active.map { it to true }
      CcAlertFilter.RESOLVED -> done
      CcAlertFilter.ALL -> active.map { it to true } + done
    }
  }
}

/** This session's resolved alerts (process lifetime; replaced, never mutated in place). */
internal object CcAlertHistory {
  private val state = MutableStateFlow<List<CcResolvedAlert>>(emptyList())
  private val seen = HashMap<String, CcAlert>()
  val resolved: StateFlow<List<CcResolvedAlert>> get() = state

  @Synchronized fun observe(active: List<CcAlert>, now: Long) {
    state.value = CcAlertRules.resolve(state.value, seen.values, active, now)
    seen.clear()
    active.forEach { seen[it.id] = it }
  }

  @Synchronized fun dismissed(alert: CcAlert, now: Long) {
    if (state.value.none { it.alert.id == alert.id }) state.value = (listOf(CcResolvedAlert(alert, now)) + state.value).take(CcAlertRules.RESOLVED_MAX)
  }
}
