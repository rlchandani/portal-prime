/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
/**
 * Everything a Control Center screen renders, as one immutable snapshot published by
 * [ControlCenter.state] (at most a few times a second). Screens derive rooms/alerts/energy from
 * it with the pure helpers ([CcRooms], [CcAlerts], [CcEnergy]); they never mutate it.
 */
@Immutable
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
    /** Bumped by the engine whenever [entities] or [pending] change; equality uses it instead of the map. */
    val entitiesVersion: Long = 0L,
    /** "Dry run · Test plan" while operator test switches are on (see [CcDebugStore]); null normally. */
    val debug: String? = null,
) {
  /**
   * Cheap equality for Compose skipping: the entity map is compared by [entitiesVersion] (the
   * engine reuses the same map instance until it changes) and [now] only to the minute, so a
   * snapshot that changed nothing a screen shows compares equal and its readers skip.
   */
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CcUiState) return false
    return entitiesVersion == other.entitiesVersion && minute == other.minute && connection == other.connection &&
        registry === other.registry && plan == other.plan && planSource == other.planSource && settings == other.settings &&
        toast == other.toast && energyPrefs == other.energyPrefs && energyToday == other.energyToday &&
        energyWeek == other.energyWeek && appliancesDone == other.appliancesDone && dismissed == other.dismissed &&
        sceneRun == other.sceneRun && debug == other.debug
  }

  override fun hashCode(): Int = (entitiesVersion * HASH_PRIME + minute).hashCode()

  /** [now] to the minute — what durations ("open 17 min") and the clock line need. */
  val minute: Long get() = now / MS_PER_MINUTE

  val ready: Boolean get() = plan != null && (entities.isNotEmpty() || connection.phase != CcPhase.CONNECTING)

  fun entity(id: String): CcEntity? = entities[id]

  /** Entities grouped by domain, computed once per snapshot (screens ask many times per frame). */
  private val byDomain: Map<String, List<CcEntity>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    // Sort keys are lowercased once per entity, not on every comparison (this runs per HA update).
    entities.values.groupBy { it.domain }.mapValues { (_, list) ->
      list.map { it.name.lowercase() to it }.sortedBy { it.first }.map { it.second }
    }
  }
  private val activeAlerts: List<CcAlert> by lazy(LazyThreadSafetyMode.PUBLICATION) { computeAlerts() }

  /** Entities of [domain], kids-mode hiding applied. */
  fun ofDomain(domain: String): List<CcEntity> =
      byDomain[domain].orEmpty().let { list -> if (settings.kids) list.filter { !CcPolicy.hidden(it, settings) } else list }

  /** Active alerts (kids mode hides the hidden kinds; criticals always remain). Cached per snapshot. */
  fun alerts(): List<CcAlert> = activeAlerts

  private fun computeAlerts(): List<CcAlert> {
    val roomName: (String) -> String? = { id -> plan?.roomOf(id)?.name }
    return CcAlerts.active(entities, now, settings.warningAfterMin, roomName, appliancesDone)
        .filter { a -> (dismissed[a.id] ?: 0L) < now }
        .filter { a -> a.severity == CcSeverity.CRITICAL || entities[a.entityId]?.let { !CcPolicy.hidden(it, settings) } != false }
  }

  /** The household alarm panel, when HA has one (and kids mode doesn't hide it). */
  val alarm: CcEntity? get() = ofDomain("alarm_control_panel").firstOrNull()

  val people: List<CcEntity> get() = ofDomain("person")

  val weather: CcEntity? get() = entities.values.firstOrNull { it.domain == "weather" }

  /** Uses °F when HA reports temperatures in °F. Worked out once per snapshot (asked many times per frame). */
  val fahrenheit: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
    entities.values.firstOrNull { CcKind.of(it) == CcKind.TEMPERATURE }?.unit?.contains("F") == true ||
        entities.values.firstOrNull { it.domain == "weather" }?.attrs?.get("temperature_unit")?.toString()?.contains("F") == true
  }
}

private const val MS_PER_MINUTE = 60_000L
private const val HASH_PRIME = 31L

internal enum class CcPlanSource { NONE, HOME_ASSISTANT, THIS_PORTAL }

/** A short message at the bottom of the screen; [undo] offers the 5 s Undo. */
@Immutable
internal data class CcToast(val id: Long, val text: String, val undo: Boolean = false, val error: Boolean = false)

/**
 * A scene being walked through the house: a soft light visits [rooms] in order over [durationMs]
 * (~10 s), so the household can watch it work. [open] lists what was still open at the start.
 */
@Immutable
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
