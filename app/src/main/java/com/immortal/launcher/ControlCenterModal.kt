/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Centred modals (`.modal`) over their own scrim: scale .96 → 1 with the mock's spring and a
 * fade. One at a time from [CcNav.modal]; the last one stays drawn while it fades out.
 */
@Composable
internal fun CcModalHost(s: CcUiState, ui: CcUi) {
  val modal = ui.openModal
  val motion = LocalCcMotion.current
  val pop = rememberCcPresence(modal != null, if (motion) CC_MODAL_MS else 0, CcSpringEase)
  val fade = rememberCcPresence(modal != null, if (motion) CC_MODAL_FADE_MS else 0, LinearEasing)
  val last = remember { CcLast<CcModal>() }
  if (modal != null) last.value = modal
  // Nothing open and nothing fading out: cost nothing.
  if (!pop.visible(modal != null) && !fade.visible(modal != null)) return
  val page = modal ?: last.value ?: return
  ModalFrame(ui, modal != null, pop, fade) {
    when (page) {
      is CcModal.Confirm -> ConfirmModal(s, ui, page)
      is CcModal.Garage -> GarageModal(s, ui, page)
      is CcModal.Pin -> CcPinModal(s, ui, page)
      is CcModal.ScenePreview -> CcScenePreviewModal(s, ui, page)
      is CcModal.ArmCheck -> CcArmCheckModal(s, ui, page)
    }
  }
}

/** Scrim + card; the spring and fade are read only in graphicsLayer. */
@Composable
private fun ModalFrame(ui: CcUi, open: Boolean, pop: CcPresence, fade: CcPresence, content: @Composable ColumnScope.() -> Unit) {
  val c = LocalCc.current
  val lift = with(LocalDensity.current) { MODAL_LIFT.toPx() }
  Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.progress.value; compositingStrategy = CompositingStrategy.ModulateAlpha }.background(c.scrim)
        .then(if (open) Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { ui.modal(null) }
            .semantics { contentDescription = "Dismiss" } else Modifier))
    Column(Modifier.align(Alignment.Center).width(CcStyle.ModalWidth)
        .graphicsLayer {
          val shown = pop.progress.value
          val k = MODAL_START_SCALE + (1f - MODAL_START_SCALE) * shown
          scaleX = k; scaleY = k; alpha = fade.progress.value; translationY = (1f - shown) * lift
        }
        .shadow(if (c.dark) 40.dp else 24.dp, CcStyle.Modal, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(CcStyle.Modal).background(c.bg)
        .clickable(remember { MutableInteractionSource() }, indication = null) {}
        .verticalScroll(rememberScrollState())
        .padding(start = 46.dp, end = 46.dp, top = 44.dp, bottom = 40.dp), content = content)
  }
}

/** `.modal h3` (serif) + `p`. */
@Composable
internal fun CcModalTitle(title: String, body: String?, center: Boolean = false, icon: String? = null) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp, if (center) Alignment.CenterHorizontally else Alignment.Start)) {
    icon?.let { CcIcon(it, c.text, 44.dp) }
    Text(title, color = c.text, fontSize = 44.sp, lineHeight = 48.sp, fontFamily = CcStyle.Serif, textAlign = if (center) TextAlign.Center else TextAlign.Start)
  }
  body?.let {
    Text(it, color = c.sec, fontSize = 24.sp, lineHeight = 32.sp, textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp))
  }
}

/** `.modal .acts`: right-aligned buttons, each at least 200 dp. */
@Composable
internal fun CcModalActions(content: @Composable () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) { content() }
}

internal val ModalButton = Modifier.widthIn(min = 200.dp)

@Composable
private fun ColumnScope.ConfirmModal(s: CcUiState, ui: CcUi, m: CcModal.Confirm) {
  CcModalTitle(m.title, m.body)
  val e = s.entity(m.entityId)
  // A lock's confirm shows the door camera when the plan has one in the same room (as the mock does).
  val cam = if (e?.domain == "lock") s.plan?.roomOf(e.id)?.devices?.map { it.entityId }?.firstOrNull { id ->
    id.startsWith("camera.") && s.entity(id)?.let { !CcPolicy.hidden(it, s.settings) } == true
  } else null
  cam?.let { CcCameraTile(s, it, live = true, modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(26.dp))) }
  val danger = m.call.domain == "valve" && m.call.service == "close_valve"
  CcModalActions {
    CcButton("Cancel", { ui.modal(null) }, ModalButton)
    CcButton(m.confirmLabel, { ui.confirmed(m.call) }, ModalButton, style = if (danger) CcButtonStyle.DANGER else CcButtonStyle.PRIMARY,
        enabled = s.connection.canControl)
  }
  s.connection.disabledReason?.let { CcCtlNote(it, Modifier.fillMaxWidth().padding(top = 12.dp)) }
}

@Composable
private fun ColumnScope.GarageModal(s: CcUiState, ui: CcUi, m: CcModal.Garage) {
  val c = LocalCc.current
  val closing = m.call.service == "close_cover"
  val title = if (closing) "Close the garage door?" else "Open the garage door?"
  val body = if (closing) "Here's the garage camera — make sure the doorway is clear. Stop is always one tap away."
  else "Here's the garage camera. It will stay open until someone closes it."
  CcModalTitle(title, body)
  if (m.cameraId != null) CcCameraTile(s, m.cameraId, live = true, modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(26.dp)), label = "Garage")
  else Column(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(26.dp)).background(c.surface2).padding(30.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
    CcIcon("cam", c.faint, 56.dp, Modifier.size(56.dp))
    Text("No garage camera in Home Assistant", color = c.text, fontSize = 24.sp)
    Text("Check the doorway yourself before you confirm.", color = c.sec, fontSize = 20.sp, textAlign = TextAlign.Center)
  }
  CcModalActions {
    CcButton("Cancel", { ui.modal(null) }, ModalButton)
    CcButton(if (closing) "Close garage" else "Open garage", { ui.confirmed(m.call) }, ModalButton, style = CcButtonStyle.PRIMARY,
        enabled = s.connection.canControl)
  }
  s.connection.disabledReason?.let { CcCtlNote(it, Modifier.fillMaxWidth().padding(top = 12.dp)) }
}

private const val MODAL_START_SCALE = 0.96f
/** `translate(-50%,-46%)` → `-50%`: the card rises a little as it appears. */
private val MODAL_LIFT = 24.dp
