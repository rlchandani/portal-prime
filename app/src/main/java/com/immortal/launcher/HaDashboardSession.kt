/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * The screen's own state on top of [HaDashboardState]: the chosen room, optimistic tile states
 * (held until a fetch after the call lands, reverted the moment HA refuses), the toast, the
 * last-fired scene and sparkline history. Immutable; every change returns a copy. Unit-tested
 * ([HaDashboardSessionTest]).
 */
data class HaDashboardSession(
    val roomId: String? = null,
    val overrides: Map<String, HaDashboardApi.Detail> = emptyMap(),
    val pending: Set<String> = emptySet(),
    val failedId: String? = null,
    val toast: Toast? = null,
    val firedScene: String? = null,
    val history: Map<String, List<Float>> = emptyMap(),
) {
  /** A message pill; [retry] adds "Try again". [serial] makes two equal messages distinct. */
  data class Toast(val text: String, val icon: String, val retry: Action? = null, val serial: Long = 0)

  /** Something the user asked HA to do (kept so "Try again" can repeat it). */
  sealed interface Action {
    val label: String
    data class Tap(val detail: HaDashboardApi.Detail) : Action { override val label get() = detail.friendlyName }
    data class Dim(val detail: HaDashboardApi.Detail, val pct: Int) : Action { override val label get() = detail.friendlyName }
    data class Temp(val detail: HaDashboardApi.Detail, val target: Float) : Action { override val label get() = detail.friendlyName }
    data class AllOff(val roomName: String, val details: List<HaDashboardApi.Detail>) : Action { override val label get() = roomName }
    data class Scene(val id: String, val name: String) : Action { override val label get() = name }
  }

  /** The chosen room, or the first one when nothing (or a vanished room) is chosen. */
  fun room(house: HaDashboardApi.House): HaDashboardApi.Area? =
      house.areas.firstOrNull { it.id == roomId } ?: house.areas.firstOrNull()

  /** [details] with this session's optimistic states laid over them. */
  fun effective(details: List<HaDashboardApi.Detail>): List<HaDashboardApi.Detail> =
      if (overrides.isEmpty()) details else details.map { overrides[it.entityId] ?: it }

  fun select(id: String): HaDashboardSession = copy(roomId = id, failedId = null)

  /** The tiles [action] changes, as they should look straight away. */
  fun begin(action: Action): HaDashboardSession {
    val shown = optimistic(action)
    return copy(overrides = overrides + shown.associateBy { it.entityId },
        pending = pending + shown.map { it.entityId }, failedId = null,
        firedScene = (action as? Action.Scene)?.id ?: firedScene)
  }

  /** HA accepted [action]: keep showing it until the next fetch confirms. */
  fun succeeded(action: Action, serial: Long): HaDashboardSession {
    val ids = optimistic(action).map { it.entityId }.toSet()
    val next = copy(pending = pending - ids)
    return when (action) {
      is Action.Scene -> next.copy(toast = Toast("${action.name} scene on", "check", serial = serial))
      is Action.AllOff -> next.copy(toast = Toast("Everything in ${action.roomName} is off", "power", serial = serial))
      else -> next
    }
  }

  /** HA refused [action]: revert at once, ring the tile red and offer "Try again". */
  fun failed(action: Action, serial: Long): HaDashboardSession {
    val ids = optimistic(action).map { it.entityId }.toSet()
    return copy(overrides = overrides - ids, pending = pending - ids,
        failedId = ids.firstOrNull(), firedScene = if (action is Action.Scene) null else firedScene,
        toast = Toast("Couldn't change ${action.label} · Home Assistant didn't answer", "alert", action, serial))
  }

  /** Offline: nothing is sent; say why. */
  fun refusedOffline(action: Action, serial: Long): HaDashboardSession {
    val text = when (action) {
      is Action.Scene -> "Offline · scenes run when Home Assistant is back"
      is Action.Temp -> "Offline · thermostat paused"
      is Action.Dim -> "Offline · dimmer paused"
      else -> "Offline · can't change ${action.label} right now"
    }
    return copy(toast = Toast(text, "wifioff", serial = serial))
  }

  /** A fresh fetch landed: drop the optimistic states it now answers for (not ones still in flight). */
  fun fetched(): HaDashboardSession = copy(overrides = overrides.filterKeys { it in pending })

  fun dismissToast(serial: Long): HaDashboardSession =
      if (toast?.serial == serial) copy(toast = null, failedId = null) else this

  fun withHistory(id: String, values: List<Float>): HaDashboardSession = copy(history = history + (id to values))

  companion object {
    /** The tiles an action changes, in their expected new state (scenes change nothing we can predict). */
    fun optimistic(action: Action): List<HaDashboardApi.Detail> = when (action) {
      is Action.Tap -> listOf(HaEntityLook.tapped(action.detail))
      is Action.Dim -> listOf(HaEntityLook.dimmed(action.detail, action.pct))
      is Action.Temp -> listOf(action.detail.copy(targetTemp = action.target))
      is Action.AllOff -> action.details.map { it.copy(state = "off") }
      is Action.Scene -> emptyList()
    }
  }
}
