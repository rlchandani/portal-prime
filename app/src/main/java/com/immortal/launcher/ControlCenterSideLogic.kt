/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * Pure rules for the overview's side column (unit tested): which scenes get the four tiles and
 * how each looks, what the scene does in a few words, and the honest "all clear" line.
 */
internal enum class CcSceneTone { NIGHT, MOVIE, LEAVE, MORNING, NEUTRAL }

/** One "Needs attention" row: the alert, the entity it acts on, its plan room and whether it offers Close. */
@Immutable
internal data class CcAttentionRow(val alert: CcAlert, val entity: CcEntity?, val roomId: String?, val close: Boolean)

/** The attention card's whole input; equal slices skip the card. */
@Immutable
internal data class CcAttentionSlice(val rows: List<CcAttentionRow>, val allClear: String)

/** List wrappers so an equal list from a new state (same scenes / cameras) skips its card. */
@Immutable
internal data class CcSceneGrid(val tiles: List<CcSceneTile>)

@Immutable
internal data class CcCameraList(val cams: List<CcFloorTab>)

/** One scene tile: the entity to run, its look and what it touches. */
@Immutable
internal data class CcSceneTile(val entity: CcEntity, val tone: CcSceneTone, val subtitle: String)

internal object CcSideLogic {
  const val SCENE_TILES = 4
  const val ATTENTION_ROWS = 4
  const val CAMERA_TILES = 2

  private val HINTS = listOf(
      CcSceneTone.NIGHT to listOf("night", "sleep", "bed"),
      CcSceneTone.MOVIE to listOf("movie", "tv", "cinema", "film"),
      CcSceneTone.LEAVE to listOf("leave", "leaving", "away", "goodbye", "out"),
      CcSceneTone.MORNING to listOf("morning", "wake", "sunrise"),
  )

  fun tone(e: CcEntity): CcSceneTone {
    val words = "${e.name} ${e.id.substringAfter('.')}".lowercase().split(' ', '_', '-', '.').filter { it.isNotBlank() }.toSet()
    return HINTS.firstOrNull { (_, hints) -> hints.any { it in words } }?.first ?: CcSceneTone.NEUTRAL
  }

  fun icon(tone: CcSceneTone): String = when (tone) {
    CcSceneTone.NIGHT -> "moon"
    CcSceneTone.MOVIE -> "film"
    CcSceneTone.LEAVE -> "leave"
    CcSceneTone.MORNING -> "sun"
    CcSceneTone.NEUTRAL -> "sparkle"
  }

  /** Up to [SCENE_TILES] scenes/scripts: the recognisable ones first; kids don't get "leaving home". */
  fun pick(all: List<CcEntity>, kids: Boolean): List<CcEntity> =
      all.filterNot { kids && tone(it) == CcSceneTone.LEAVE }
          .sortedWith(compareBy<CcEntity> { tone(it) == CcSceneTone.NEUTRAL }.thenBy { tone(it).ordinal }.thenBy { it.name.lowercase() })
          .take(SCENE_TILES)

  /** The attention card from the active alerts (top [ATTENTION_ROWS]); the all-clear line when there are none. */
  fun attention(alerts: List<CcAlert>, entities: Map<String, CcEntity>, plan: CcPlan?, settings: ControlCenterConfig.Settings): CcAttentionSlice {
    val rows = alerts.take(ATTENTION_ROWS).map { a ->
      val e = entities[a.entityId]
      CcAttentionRow(a, e, plan?.roomOf(a.entityId)?.id, a.action == CcAlertAction.CLOSE && e?.domain == "cover")
    }
    val clear = if (rows.isEmpty()) allClear(entities.values.filter { !CcPolicy.hidden(it, settings) }) else ""
    return CcAttentionSlice(rows, clear)
  }

  /** The scene grid's tiles, picked and described once per state change. */
  fun tiles(all: List<CcEntity>, plan: CcPlan?, kids: Boolean): List<CcSceneTile> =
      pick(all, kids).map { CcSceneTile(it, tone(it), subtitle(it, plan)) }

  /** What the scene touches: "Living room", "Kitchen & Dining", "Walks the house", "4 devices". */
  fun subtitle(e: CcEntity, plan: CcPlan?): String {
    val members = e.members
    val rooms = members.mapNotNull { plan?.roomOf(it)?.name }.distinct()
    return when {
      rooms.size == 1 -> rooms[0]
      rooms.size == 2 -> "${rooms[0]} & ${rooms[1]}"
      rooms.size > 2 -> if (members.size >= CcPolicy.WHOLE_HOUSE) "Walks the house" else "${rooms.size} rooms"
      members.isNotEmpty() -> if (members.size == 1) "1 device" else "${members.size} devices"
      e.domain == "script" -> "Runs a script"
      else -> "Scene"
    }
  }

  /** The "All good" row's honest second line, from what the house actually has. */
  fun allClear(entities: Collection<CcEntity>): String {
    val locks = entities.filter { it.domain == "lock" && !it.unavailable }
    val garages = entities.filter { CcKind.of(it) == CcKind.GARAGE && !it.unavailable }
    val parts = buildList {
      if (locks.isNotEmpty() && locks.all { it.state == "locked" }) add(if (locks.size == 1) "Door locked" else "Doors locked")
      if (garages.isNotEmpty() && garages.none { it.isOpen }) add("garage shut")
    }
    return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }.ifBlank { "Nothing needs you right now" }
  }

  /** Alert icon tile tone: critical red, warning amber, info neutral. */
  fun tone(a: CcAlert): CcTone = when (a.severity) {
    CcSeverity.CRITICAL -> CcTone.RED
    CcSeverity.WARNING -> CcTone.AMBER
    CcSeverity.INFO -> CcTone.NEUTRAL
  }

  fun icon(a: CcAlert): String = when (a.kind) {
    CcKind.APPLIANCE -> CcPlanModel.applianceIcon(a.entityId)
    else -> CcIcons.forKind(a.kind)
  }
}
