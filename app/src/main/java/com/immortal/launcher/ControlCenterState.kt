/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Everything a Control Center screen renders, as one immutable snapshot published by
 * [ControlCenter.state] (at most a few times a second). Screens derive rooms/alerts/energy from
 * it with the pure helpers ([CcRooms], [CcAlerts], [CcEnergy]); they never mutate it.
 */
internal data class CcUiState(
    val connection: CcConnection = CcConnection(),
    /** HA state with optimistic overrides applied. */
    val entities: Map<String, CcEntity> = emptyMap(),
    /** Entities with a call in flight (tiles show a soft progress ring). */
    val pending: Set<String> = emptySet(),
    val registry: CcRegistry? = null,
    /** Null until the plan has been looked up; [CcPlan.EMPTY] = no plan yet (editor CTA). */
    val plan: CcPlan? = null,
    val planSource: CcPlanSource = CcPlanSource.NONE,
    val settings: ControlCenterConfig.Settings = ControlCenterConfig.Settings(),
    val toast: CcToast? = null,
    val energyPrefs: CcEnergyPrefs? = null,
    val energyToday: CcEnergySeries? = null,
    val energyWeek: CcEnergySeries? = null,
    /** Appliances that finished recently: entity id → when (for "Washer is done"). */
    val appliancesDone: Map<String, Long> = emptyMap(),
    /** Dismissed/snoozed alert ids → until (epoch ms). */
    val dismissed: Map<String, Long> = emptyMap(),
    val sceneRun: CcSceneRun? = null,
    val now: Long = 0L,
) {
  val ready: Boolean get() = plan != null && (entities.isNotEmpty() || connection.phase != CcPhase.CONNECTING)

  fun entity(id: String): CcEntity? = entities[id]

  /** Entities of [domain], kids-mode hiding applied. */
  fun ofDomain(domain: String): List<CcEntity> =
      entities.values.filter { it.domain == domain && !CcPolicy.hidden(it, settings) }.sortedBy { it.name.lowercase() }

  /** Active alerts (kids mode hides the hidden kinds; criticals always remain). */
  fun alerts(): List<CcAlert> {
    val roomName: (String) -> String? = { id -> plan?.roomOf(id)?.name }
    return CcAlerts.active(entities, now, settings.warningAfterMin, roomName, appliancesDone)
        .filter { a -> (dismissed[a.id] ?: 0L) < now }
        .filter { a -> a.severity == CcSeverity.CRITICAL || entities[a.entityId]?.let { !CcPolicy.hidden(it, settings) } != false }
  }

  /** The household alarm panel, when HA has one (and kids mode doesn't hide it). */
  val alarm: CcEntity? get() = ofDomain("alarm_control_panel").firstOrNull()

  val people: List<CcEntity> get() = ofDomain("person")

  val weather: CcEntity? get() = entities.values.firstOrNull { it.domain == "weather" }

  /** Uses °F when HA reports temperatures in °F. */
  val fahrenheit: Boolean
    get() = entities.values.firstOrNull { CcKind.of(it) == CcKind.TEMPERATURE }?.unit?.contains("F") == true ||
        entities.values.firstOrNull { it.domain == "weather" }?.attrs?.get("temperature_unit")?.toString()?.contains("F") == true
}

internal enum class CcPlanSource { NONE, HOME_ASSISTANT, THIS_PORTAL }

/** A short message at the bottom of the screen; [undo] offers the 5 s Undo. */
internal data class CcToast(val id: Long, val text: String, val undo: Boolean = false, val error: Boolean = false)

/**
 * A scene being walked through the house: a soft light visits [rooms] in order over [durationMs]
 * (~10 s), so the household can watch it work. [open] lists what was still open at the start.
 */
internal data class CcSceneRun(
    val sceneId: String,
    val name: String,
    val rooms: List<String>,
    val startedAt: Long,
    val durationMs: Long,
    val open: List<String> = emptyList(),
    val canUndo: Boolean = false,
) {
  fun progress(now: Long): Float = ((now - startedAt).toFloat() / durationMs).coerceIn(0f, 1f)
  fun finished(now: Long): Boolean = now - startedAt >= durationMs

  companion object {
    const val STEP_MS = 1_400L
    const val MIN_MS = 4_000L
    const val MAX_MS = 12_000L

    /** Rooms touched by the scene's members, ordered floor by floor, left to right. */
    fun rooms(plan: CcPlan?, members: List<String>): List<String> {
      if (plan == null) return emptyList()
      return plan.floors.flatMap { f -> f.rooms.filter { r -> r.devices.any { it.entityId in members } }.sortedBy { it.rect.x * 100 + it.rect.y } }
          .map { it.id }
    }

    fun duration(rooms: Int): Long = (rooms * STEP_MS).coerceIn(MIN_MS, MAX_MS)
  }
}
