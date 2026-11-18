/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** First run of a whole-house scene (`scene-night`): what it touches and what's still open. */
@Composable
internal fun CcScenePreviewModal(s: CcUiState, ui: CcUi, m: CcModal.ScenePreview) {
  val scene = s.entity(m.sceneId)
  if (scene == null) {
    CcModalTitle("Scene not found", "Home Assistant no longer has this scene.")
    CcModalActions { CcButton("Close", { ui.modal(null) }, ModalButton) }
    return
  }
  val rooms = CcSceneRun.rooms(s.plan, scene.members)
  val roomsLine = CcModalLogic.roomsLine(s.plan, rooms)
  CcModalTitle(scene.name, "Here's what will happen. After the first run it's one tap, with Undo.", icon = sceneIcon(scene.name))
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    CcModalLogic.sceneLines(scene.members, s.entities).forEach { CcCtlCheckRow(it) }
    roomsLine?.let { CcCtlCheckRow(CcCheckLine("home", "In $it")) }
    CcModalLogic.openThings(s.entities).forEach { e ->
      CcCtlCheckRow(CcModalLogic.openLine(e).let { it.copy(text = it.text + " — a scene won't close it") })
    }
  }
  CcModalActions {
    CcButton("Not now", { ui.modal(null) }, ModalButton)
    CcButton("Run ${scene.name}", { if (ControlCenter.runScene(ui.context, scene)) ui.modal(null) }, ModalButton,
        icon = sceneIcon(scene.name), style = CcButtonStyle.EMBER, enabled = s.connection.canControl)
  }
  s.connection.disabledReason?.let { CcCtlNote(it, Modifier.fillMaxWidth().padding(top = 12.dp)) }
}

/** Arming (`security-check`): what's open, a garage we can close, then Arm anyway (PIN if HA wants one). */
@Composable
internal fun CcArmCheckModal(s: CcUiState, ui: CcUi, m: CcModal.ArmCheck) {
  val panel = s.entity(m.panelId)
  val mode = CcModalLogic.modeName(m.mode)
  var error by remember(m) { mutableStateOf<String?>(null) }
  var busy by remember(m) { mutableStateOf(false) }
  val open = CcModalLogic.openThings(s.entities)
  CcModalTitle("Arm $mode?", CcModalLogic.openCountLine(open.size))
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    open.forEach { e ->
      val line = CcModalLogic.openLine(e)
      val pending = e.id in s.pending
      CcCtlCheckRow(line.copy(text = line.text + if (CcModalLogic.closable(e)) "" else " — will be bypassed")) {
        when {
          pending -> CcCtlBusy(true)
          CcModalLogic.closable(e) -> CcButton("Close it for me", { ui.act(e, CcCalls.coverClose(e)) }, height = 60, enabled = s.connection.canControl)
        }
      }
    }
  }
  error?.let { CcCtlNote(it, Modifier.fillMaxWidth().padding(top = 12.dp)) }
  s.connection.disabledReason?.let { CcCtlNote(it, Modifier.fillMaxWidth().padding(top = 12.dp)) }
  CcModalActions {
    CcButton("Cancel", { ui.modal(null) }, ModalButton)
    CcButton(if (open.isEmpty()) "Arm $mode" else "Arm anyway", {
      if (panel == null) { error = "Home Assistant has no alarm panel"; return@CcButton }
      if (panel.attrs["code_arm_required"] == true) {
        ui.modal(CcModal.Pin(panel.id, m.mode, "Arm $mode", "Enter the alarm code"))
      } else {
        busy = true; error = null
        ControlCenter.alarm(ui.context, panel, m.mode, null) { ok, msg ->
          busy = false
          if (ok) { if (ui.nav.modal == m) ui.modal(null) } else error = msg?.let { "Home Assistant said: $it" } ?: s.connection.disabledReason ?: "Couldn't arm"
        }
      }
    }, ModalButton, style = CcButtonStyle.PRIMARY, enabled = s.connection.canControl && !busy)
  }
}
