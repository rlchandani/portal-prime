/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf

/** The plan's three lenses on the same map (Live / Climate / Energy). */
internal enum class CcLens { LIVE, CLIMATE, ENERGY }

/** What slides in from the right: a room, then a device inside it (a back-stack). */
internal sealed interface CcSheet {
  data class Room(val roomId: String) : CcSheet
  data class Device(val entityId: String) : CcSheet
}

/** Centred modal dialogs; at most one at a time. */
internal sealed interface CcModal {
  /** "Unlock the front door?" / "Shut off the water?" — [call] runs on confirm. */
  data class Confirm(val entityId: String, val call: CcCall, val title: String, val body: String, val confirmLabel: String) : CcModal
  /** Garage open/close: shows the garage camera ([cameraId], may be null) before confirming. */
  data class Garage(val entityId: String, val call: CcCall, val cameraId: String?) : CcModal
  /** PIN pad for the alarm ([panelId] + [mode]) or for leaving kids mode ([panelId] null). */
  data class Pin(val panelId: String?, val mode: CcAlarmMode?, val title: String, val subtitle: String) : CcModal
  /** First run of a whole-house scene: what it will do, what's open. */
  data class ScenePreview(val sceneId: String) : CcModal
  /** Arming: what's open is checked first. */
  data class ArmCheck(val panelId: String, val mode: CcAlarmMode) : CcModal
}

/** What the plan card reads from [CcNav]: floor, lens and the highlighted room. */
@Immutable
internal data class CcPlanView(val floorId: String?, val lens: CcLens, val highlightRoom: String?)

/** Screen-local navigation (immutable; replaced, never mutated). */
@Immutable
internal data class CcNav(
    val screen: CcScreen = CcScreen.MAP,
    val floorId: String? = null,
    val lens: CcLens = CcLens.LIVE,
    val sheets: List<CcSheet> = emptyList(),
    val modal: CcModal? = null,
    /** Camera shown in the full view (Cameras screen), null = grid. */
    val cameraFull: String? = null,
    /** Room highlighted on the plan (open sheet or scene walk). */
    val highlightRoom: String? = null,
) {
  val topSheet: CcSheet? get() = sheets.lastOrNull()
  val openRoomId: String? get() = sheets.filterIsInstance<CcSheet.Room>().lastOrNull()?.roomId
}

/**
 * What every screen calls to move around and act. Actions pass through [CcPolicy]: anything that
 * asks first opens its modal; everything else goes straight to [ControlCenter.send].
 */
@Stable
internal class CcUi(val context: Context, private val navState: MutableState<CcNav>, private val stateOf: () -> CcUiState) {
  /** The whole navigation; reading it in composition recomposes on ANY change (prefer the narrow reads below). */
  val nav: CcNav get() = navState.value
  private fun set(n: CcNav) { navState.value = n }

  // Narrow reads: a composable reading one of these recomposes only when that part changes, so a
  // lens tap doesn't recompose the shell, the sheet host, the modal host or the parked screens.
  private val screenOf = derivedStateOf { navState.value.screen }
  private val floorOf = derivedStateOf { navState.value.floorId }
  private val planViewOf = derivedStateOf { navState.value.let { CcPlanView(it.floorId, it.lens, it.highlightRoom) } }
  private val sheetsOf = derivedStateOf { navState.value.sheets }
  private val modalOf = derivedStateOf { navState.value.modal }
  private val cameraFullOf = derivedStateOf { navState.value.cameraFull }
  val screen: CcScreen get() = screenOf.value
  val floorId: String? get() = floorOf.value
  val planView: CcPlanView get() = planViewOf.value
  val sheets: List<CcSheet> get() = sheetsOf.value
  val topSheet: CcSheet? get() = sheetsOf.value.lastOrNull()
  val openModal: CcModal? get() = modalOf.value
  val cameraFull: String? get() = cameraFullOf.value

  /** Uptime of the last touch anywhere on the Control Center (background work waits for quiet). */
  @Volatile var lastInputAt: Long = 0L
    private set

  fun touched(now: Long) { lastInputAt = now }

  fun go(screen: CcScreen) {
    if (CcPolicy.screenHidden(screen, stateOf().settings)) return
    set(nav.copy(screen = screen, sheets = emptyList(), modal = null, cameraFull = null, highlightRoom = null))
  }
  fun floor(id: String) = set(nav.copy(floorId = id, sheets = emptyList(), highlightRoom = null))
  fun lens(l: CcLens) = set(nav.copy(lens = l))
  fun openRoom(roomId: String) = set(nav.copy(sheets = listOf(CcSheet.Room(roomId)), highlightRoom = roomId))
  fun openDevice(entityId: String) = set(nav.copy(sheets = nav.sheets + CcSheet.Device(entityId)))
  fun back() = set(if (nav.modal != null) nav.copy(modal = null) else nav.copy(sheets = nav.sheets.dropLast(1),
      highlightRoom = if (nav.sheets.size <= 1) null else nav.highlightRoom))
  fun closeSheets() = set(nav.copy(sheets = emptyList(), highlightRoom = null))
  fun modal(m: CcModal?) = set(nav.copy(modal = m))
  fun camera(id: String?) = set(nav.copy(screen = CcScreen.CAMERAS, cameraFull = id))
  /** System Back: modal → sheet → camera full view → Home map. Returns false when nothing to close. */
  fun handleBack(): Boolean = when {
    nav.modal != null || nav.sheets.isNotEmpty() -> { back(); true }
    nav.cameraFull != null -> { set(nav.copy(cameraFull = null)); true }
    nav.screen != CcScreen.MAP -> { go(CcScreen.MAP); true }
    else -> false
  }

  /** Acts on [e] with [call], asking first where the household chose so. */
  fun act(e: CcEntity, call: CcCall, onResult: ((Boolean, String?) -> Unit)? = null) {
    val s = stateOf()
    if (CcPolicy.hidden(e, s.settings) || CcPolicy.readOnly(e, s.settings)) return
    when (CcPolicy.ask(e, call, s.settings, ControlCenterConfig.scenesSeen(context))) {
      CcPolicy.Ask.NONE -> if (e.domain == "scene" || e.domain == "script") ControlCenter.runScene(context, e)
          else if (!ControlCenter.send(context, call, undoable = onResult == null, onResult = onResult)) onResult?.invoke(false, null)
      CcPolicy.Ask.CONFIRM -> modal(confirmFor(e, call))
      CcPolicy.Ask.CAMERA_CONFIRM -> modal(CcModal.Garage(e.id, call, cameraNear(e, s)))
      CcPolicy.Ask.PIN -> modal(CcModal.Pin(e.id, CcAlarmMode.entries.firstOrNull { it.service == call.service }, "Disarm",
          "Alarm is ${e.state.replace('_', ' ')}"))
    }
  }

  fun runScene(e: CcEntity) = act(e, CcCalls.run(e))

  /** Sends a confirmed call (from a modal) and closes it. */
  fun confirmed(call: CcCall) {
    val e = call.entityId?.let { stateOf().entity(it) }
    if (e != null && (e.domain == "scene" || e.domain == "script")) ControlCenter.runScene(context, e, confirmed = true)
    else ControlCenter.send(context, call, confirmed = true)
    modal(null)
  }

  private fun confirmFor(e: CcEntity, call: CcCall): CcModal = when {
    e.domain == "scene" -> CcModal.ScenePreview(e.id)
    e.domain == "script" -> CcModal.Confirm(e.id, call, CcModalLogic.confirmTitle(e, call),
        "This Home Assistant script can arm, unlock or open things, so it asks first.", "Run")
    e.domain == "valve" -> CcModal.Confirm(e.id, call, CcModalLogic.confirmTitle(e, call),
        "${e.name} will ${if (call.service == "close_valve") "close" else "open"}.", if (call.service == "close_valve") "Shut off" else "Open")
    else -> CcModal.Confirm(e.id, call, CcModalLogic.confirmTitle(e, call), lockLine(e), "Unlock")
  }

  private fun lockLine(e: CcEntity): String {
    val who = e.changedBy ?: stateOf().people.firstOrNull { it.attrs["user_id"] == e.userId }?.name
    return "Locked${who?.let { " by $it" } ?: ""}. Anyone at the door can open it once unlocked."
  }

  /** The camera that watches this garage: same plan room first, else a camera named like it. */
  private fun cameraNear(e: CcEntity, s: CcUiState): String? {
    val room = s.plan?.roomOf(e.id)
    val inRoom = room?.devices?.map { it.entityId }?.firstOrNull { it.startsWith("camera.") }
    return inRoom ?: s.entities.keys.firstOrNull { it.startsWith("camera.") && "garage" in it }
  }
}
