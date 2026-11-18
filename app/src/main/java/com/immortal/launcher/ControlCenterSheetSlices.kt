/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * Small immutable slices of [CcUiState] for the sheet (pure, unit tested). Pages derive them once
 * per snapshot and hand them down, so a child whose slice is equal skips composition and draw
 * even though a new state was published.
 */
@Immutable
internal data class CcRoomChip(val roomId: String, val label: String)

/** Everything one device page shows besides the entity itself. */
@Immutable
internal data class CcDeviceSlice(
    val entity: CcEntity?,
    val gate: CcGate,
    val pending: Boolean,
    val backLabel: String,
    /** Tile-style status ("Open · 17 min"), minute-accurate. */
    val status: String = "",
    val readOnly: Boolean = false,
    val humidity: Int? = null,
    val rooms: List<CcRoomChip> = emptyList(),
    /** Lock: "Locked by Sam · 7:31 pm"; garage: "Since 7:25 pm · Sam". */
    val who: String = "",
    val cameraId: String? = null,
    val done: Boolean = false,
    val askUnlock: Boolean = false,
    val askGarage: Boolean = false,
    val fahrenheit: Boolean = false,
)

/** The room's scene chips and the lights "All off" turns off (compared by value, so equal parts skip). */
@Immutable
internal data class CcRoomScenes(val scenes: List<CcEntity>, val lightsOn: List<CcEntity>, val hasLights: Boolean, val canControl: Boolean)

/** An alert card; [closable] is the garage it can close in place. */
@Immutable
internal data class CcRoomAlert(val alert: CcAlert, val closable: CcEntity?)

/** The room page header chips. */
@Immutable
internal data class CcRoomHead(val temp: String?, val humidity: String?, val presence: Pair<String, Boolean>?, val lightsOn: Int)

/** The room page, derived once per snapshot. */
@Immutable
internal data class CcRoomSlice(
    val room: CcRoom,
    val head: CcRoomHead,
    val alerts: List<CcRoomAlert>,
    val contents: CcRoomContents,
    val climateRoomTemp: Double?,
    val climateGate: CcGate?,
    val climateReadOnly: Boolean,
    val scenes: CcRoomScenes,
    val canControl: Boolean,
    val kids: Boolean,
    val stale: Boolean,
    /** [CcUiState.now] floored to the minute (garage "open 17 min"). */
    val minuteNow: Long,
    val pending: Set<String>,
    val done: Set<String>,
    val askGarage: Boolean,
    val askUnlock: Boolean,
    val askValve: Boolean,
)

internal object CcSlices {
  private const val MS_PER_MIN = 60_000L

  fun minuteNow(s: CcUiState): Long = s.minute * MS_PER_MIN

  fun room(s: CcUiState, roomId: String): CcRoomSlice? {
    val room = s.plan?.room(roomId) ?: return null
    val view = CcRooms.view(room, s.entities, s.entities.values.filter { it.domain == "climate" })
    val lightsOn = CcSheetLogic.lightsOn(s, room)
    val contents = CcSheetLogic.contents(s, room)
    val ids = room.devices.map { it.entityId }.toSet()
    return CcRoomSlice(
        room = room,
        head = CcRoomHead(view.temperature?.let { CcSheetLogic.temp(it) }, view.humidity?.let { "$it%" }, CcSheetLogic.presence(s, room), lightsOn.size),
        alerts = CcSheetLogic.alerts(s, room).map { a ->
          CcRoomAlert(a, s.entity(a.entityId)?.takeIf { a.action == CcAlertAction.CLOSE && CcModalLogic.closable(it) })
        },
        contents = contents,
        climateRoomTemp = CcRooms.view(room, s.entities).temperature ?: contents.climate?.currentTemp,
        climateGate = contents.climate?.let { ccGate(s, it) },
        climateReadOnly = contents.climate?.let { CcPolicy.readOnly(it, s.settings) } == true,
        scenes = CcRoomScenes(CcSheetLogic.scenes(s, room), lightsOn, room.devices.any { it.entityId.startsWith("light.") }, s.connection.canControl),
        canControl = s.connection.canControl,
        kids = s.settings.kids,
        stale = s.connection.phase == CcPhase.STALE,
        minuteNow = minuteNow(s),
        pending = s.pending.filterTo(HashSet()) { it in ids },
        done = s.appliancesDone.keys.filterTo(HashSet()) { it in ids },
        askGarage = s.settings.askGarage,
        askUnlock = s.settings.askUnlock,
        askValve = s.settings.askValve,
    )
  }

  /** The device page; [stack] is the sheet back-stack (for the "‹ Room" label). */
  fun device(s: CcUiState, entityId: String, stack: List<CcSheet>): CcDeviceSlice {
    val e = s.entity(entityId)?.takeIf { !CcPolicy.hidden(it, s.settings) }
    val prevRoom = stack.dropLast(1).filterIsInstance<CcSheet.Room>().lastOrNull()?.let { s.plan?.room(it.roomId)?.name }
    val back = prevRoom ?: e?.let { s.plan?.roomOf(it.id)?.name }?.takeIf { stack.size > 1 } ?: "Back"
    if (e == null) return CcDeviceSlice(null, CcGate(false, null), false, back)
    val done = e.id in s.appliancesDone
    val now = minuteNow(s)
    val base = CcDeviceSlice(e, ccGate(s, e), e.id in s.pending, back, status = CcSheetLogic.status(e, now, done), done = done,
        readOnly = CcPolicy.readOnly(e, s.settings), askUnlock = s.settings.askUnlock, askGarage = s.settings.askGarage)
    return when (CcKind.of(e)) {
      CcKind.CLIMATE -> {
        val room = s.plan?.roomOf(e.id)
        base.copy(fahrenheit = s.fahrenheit, humidity = room?.let { CcRooms.view(it, s.entities, listOf(e)).humidity } ?: e.humidity,
            rooms = CcSheetLogic.thermostatRooms(s.plan, e.id).map { r ->
              val t = CcRooms.view(r, s.entities, listOf(e)).temperature
              CcRoomChip(r.id, listOfNotNull(r.name, t?.let { CcSheetLogic.temp(it) }).joinToString(" · "))
            })
      }
      CcKind.LOCK -> base.copy(who = lockWhoWhen(s, e))
      CcKind.GARAGE -> base.copy(who = garageSince(s, e), cameraId = CcSheetLogic.garageCamera(s, e))
      else -> base
    }
  }

  /** "Locked by Sam · 7:31 pm" (HA's changed_by or the person behind the change). */
  fun lockWhoWhen(s: CcUiState, e: CcEntity): String {
    val who = CcSheetLogic.who(s, e)
    val verb = CcSheetLogic.lockText(e.state)
    val parts = listOfNotNull(who?.let { "$verb by $it" } ?: verb.takeIf { e.lastChanged > 0 }, e.lastChanged.takeIf { it > 0 }?.let { ccTime(it) })
    return parts.joinToString(" · ").ifEmpty { "Home Assistant hasn't said who changed it" }
  }

  fun garageSince(s: CcUiState, e: CcEntity): String {
    if (e.lastChanged <= 0) return ""
    val who = CcSheetLogic.who(s, e)
    val verb = if (e.isOpen) "Since" else "Closed at"
    return listOfNotNull("$verb ${ccTime(e.lastChanged)}", who).joinToString(" · ")
  }
}
