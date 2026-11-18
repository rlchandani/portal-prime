/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.ControlCenterSettings

/**
 * PIN pad (`security-pin`): the alarm code goes to Home Assistant with the arm/disarm call (never
 * stored here); the kids-mode PIN is checked against the salted hash on this Portal. The alarm pad
 * auto-submits at [ControlCenterConfig.Settings.pinLength] digits; the kids pad never shows its PIN's
 * length (dots only as typed, then "Leave kids mode") and shares [CcKidsPinAttempts]' tries and rest.
 * A wrong code shakes the dots.
 */
@Composable
internal fun CcPinModal(s: CcUiState, ui: CcUi, m: CcModal.Pin) {
  val kids = m.panelId == null
  if (kids && !ControlCenterConfig.hasKidsPin(ui.context)) { NoKidsPin(ui); return }
  var entry by remember(m) {
    mutableStateOf(if (kids) CcKidsPinAttempts.open(SystemClock.elapsedRealtime()) else CcPinEntry(s.settings.pinLength))
  }
  fun put(e: CcPinEntry) { entry = e; if (kids) CcKidsPinAttempts.keep(e) }
  val panel = m.panelId?.let { s.entity(it) }
  fun submit(code: String) {
    if (kids) {
      if (ControlCenterConfig.checkKidsPin(ui.context, code)) {
        CcKidsPinAttempts.reset()
        ControlCenterSettings.set(ui.context, "portalMode", ControlCenterConfig.MODE_FULL)
        ui.modal(null)
      } else put(entry.failed(SystemClock.elapsedRealtime()))
      return
    }
    if (panel == null) { entry = entry.unchecked("Home Assistant has no alarm panel"); return }
    ControlCenter.alarm(ui.context, panel, m.mode ?: CcAlarmMode.DISARM, code) { ok, msg ->
      when {
        ok -> if (ui.nav.modal == m) ui.modal(null)
        msg == null -> put(entry.unchecked(s.connection.disabledReason ?: "Couldn't reach Home Assistant"))
        else -> put(entry.failed(SystemClock.elapsedRealtime()))
      }
    }
  }
  CcModalTitle(m.title, m.subtitle, center = true)
  PinDots(entry)
  PinStatus(entry)
  val onKey: (Char) -> Unit = { key ->
    val (next, code) = entry.press(key, SystemClock.elapsedRealtime())
    put(next)
    code?.let(::submit)
  }
  PinPad(enabled = !entry.busy && (kids || s.connection.canControl), onCancel = { ui.modal(null) }, onKey = onKey)
  if (!entry.autoSubmit) CcModalActions {
    CcButton("Leave kids mode", { onKey(CcPinEntry.ENTER) }, ModalButton, style = CcButtonStyle.PRIMARY,
        enabled = !entry.busy && entry.digits.isNotEmpty())
  }
  Text(if (kids) "Checked on this Portal, never sent anywhere" else "Checked by Home Assistant, never stored on the Portal",
      color = LocalCc.current.sec, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 20.dp))
}

@Composable
private fun PinDots(entry: CcPinEntry) {
  val c = LocalCc.current
  val shake = remember { Animatable(0f) }
  val shakePx = with(LocalDensity.current) { SHAKE.toPx() }
  LaunchedEffect(entry.shakes) {
    if (entry.shakes == 0) return@LaunchedEffect
    shake.animateTo(0f, keyframes {
      durationMillis = SHAKE_MS
      -shakePx at SHAKE_MS / 5; shakePx at SHAKE_MS * 2 / 5; -shakePx at SHAKE_MS * 3 / 5; shakePx at SHAKE_MS * 4 / 5
    })
  }
  // Without auto-submit the code's length is secret: only the digits typed so far show as dots.
  val shown = if (entry.autoSubmit) entry.length else entry.digits.length
  Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp).height(DOT).graphicsLayer { translationX = shake.value }
      .semantics { contentDescription = if (entry.autoSubmit) "${entry.digits.length} of ${entry.length} digits" else "${entry.digits.length} digits" },
      horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally)) {
    repeat(shown) { i ->
      val filled = i < entry.digits.length
      Box(Modifier.size(DOT).clip(CircleShape).border(3.dp, c.text, CircleShape).then(if (filled) Modifier.background(c.text) else Modifier))
    }
  }
}

@Composable
private fun PinStatus(entry: CcPinEntry) {
  val c = LocalCc.current
  Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
    when {
      entry.busy -> CcCtlBusy(true, 28)
      entry.error != null -> Text(entry.error, color = c.red, fontSize = 21.sp)
    }
  }
}

/** `.pad`: 3 × 150 dp columns, 110 dp keys; Cancel and Delete as quiet text keys. */
@Composable
private fun PinPad(enabled: Boolean, onCancel: () -> Unit, onKey: (Char) -> Unit) {
  Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    listOf("123", "456", "789").forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { row.forEach { d -> PinKey(d.toString(), d.toString(), enabled) { onKey(d) } } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      PinKey("Cancel", "Cancel", true, quiet = true, onClick = onCancel)
      PinKey("0", "0", enabled) { onKey('0') }
      PinKey("Delete", "Delete last digit", enabled, quiet = true) { onKey(CcPinEntry.DELETE) }
    }
  }
}

@Composable
private fun PinKey(label: String, description: String, enabled: Boolean, quiet: Boolean = false, onClick: () -> Unit) {
  val c = LocalCc.current
  Box(Modifier.width(150.dp).height(110.dp).alpha(if (enabled) 1f else CC_DISABLED_ALPHA).clip(CcStyle.Pill)
      .then(if (quiet) Modifier else Modifier.background(c.surface2))
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description },
      contentAlignment = Alignment.Center) {
    Text(label, color = if (quiet) c.sec else c.text, fontSize = if (quiet) 22.sp else 44.sp)
  }
}

/** Kids mode with no PIN set can only be left from Hearthpane Settings. */
@Composable
private fun NoKidsPin(ui: CcUi) {
  CcModalTitle("Leave kids mode", "No kids PIN is set on this Portal, so kids mode can only be turned off from " +
      "Hearthpane Settings › Home Assistant. A grown-up can set a PIN there too.", center = true)
  CcModalActions { CcButton("OK", { ui.modal(null) }, ModalButton, style = CcButtonStyle.PRIMARY) }
}

private val SHAKE = 14.dp
private val DOT = 30.dp
private const val SHAKE_MS = 450
