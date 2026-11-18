/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.ControlCenterSettings

/**
 * Home settings (mock `.st`): section list on the left, the section's rows on the right. Every
 * write goes through [ControlCenterSettings.set] so it validates like the phone remote; values
 * are read back from [CcUiState.settings] after the runtime reloads.
 */
@Composable
internal fun CcSettingsScreen(s: CcUiState, ui: CcUi) {
  // Only these few values matter here; an HA entity update yields an equal slice and the body skips.
  val regLine = remember(s.registry) { CcSetLogic.registryLine(s.registry) }
  val plan = remember(s.planSource, s.plan) { CcSetLogic.planSource(s.planSource, s.plan) }
  val slice = CcSetSlice(s.settings, s.connection.phase, s.connection.disabledReason, s.entities.size, regLine, s.registry == null,
      plan.first, plan.second)
  CcSettingsBody(slice, ui)
}

/** Everything the settings screen shows that comes from the runtime. */
@Immutable
internal data class CcSetSlice(
    val settings: ControlCenterConfig.Settings,
    val phase: CcPhase,
    val reason: String?,
    val entityCount: Int,
    val registryLine: String,
    val registryMissing: Boolean,
    val planWhere: String,
    val planWhereSub: String,
)

@Composable
private fun CcSettingsBody(slice: CcSetSlice, ui: CcUi) {
  var section by remember { mutableStateOf(CcSetSection.PORTALS) }
  var pinFor by remember { mutableStateOf<CcPinPurpose?>(null) }
  var pinVersion by remember { mutableStateOf(0) }
  val hasPin = remember(pinVersion) { ControlCenterConfig.hasKidsPin(ui.context) }
  val write: (String, Any) -> Unit = remember(ui) { { key, value -> ControlCenterSettings.set(ui.context, key, value) } }
  val st = slice.settings
  Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
      CcSettingsNav(section) { section = it }
      Column(Modifier.weight(1f).fillMaxHeight().ccCard().verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 30.dp)) {
        when (section) {
          CcSetSection.PORTALS -> CcSetPortals(st.portalMode, hasPin, ui, write) { pinFor = CcPinPurpose.ENTER_KIDS }
          CcSetSection.KIDS -> CcSetKids(st, hasPin, write) { pinFor = CcPinPurpose.CHANGE }
          CcSetSection.CONFIRM -> CcSetConfirm(st, write)
          CcSetSection.BANNERS -> CcSetBanners(st, ui, write)
          CcSetSection.LOOK -> CcSetLook(st, ui, write)
          CcSetSection.HA -> CcSetHomeAssistant(slice, ui)
        }
      }
    }
    pinFor?.let { purpose ->
      CcPinSetOverlay(onCancel = { pinFor = null }) { pin ->
        val ok = ControlCenterConfig.setKidsPin(ui.context, pin)
        pinFor = null
        pinVersion++
        if (ok && purpose == CcPinPurpose.ENTER_KIDS) { write("portalMode", ControlCenterConfig.MODE_KIDS); ui.go(CcScreen.MAP) }
      }
    }
  }
}

internal enum class CcPinPurpose { ENTER_KIDS, CHANGE }

/** Mock `.stNav`. */
@Composable
private fun CcSettingsNav(selected: CcSetSection, onSelect: (CcSetSection) -> Unit) {
  val c = LocalCc.current
  Column(Modifier.width(500.dp).fillMaxHeight().ccCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    CcSetSection.entries.forEach { sec ->
      val on = sec == selected
      Row(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(24.dp)).background(if (on) c.sel else androidx.compose.ui.graphics.Color.Transparent)
          .clickable(role = Role.Tab) { onSelect(sec) }.semantics { contentDescription = if (on) "${sec.label}, selected" else sec.label }
          .padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        CcIcon(sec.icon, c.sec, 34.dp)
        Text(sec.label, color = c.text, fontSize = 23.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
      }
    }
  }
}

/** `.stBody h3` + `.stBody > p`. */
@Composable
internal fun CcSetHeading(title: String, body: String) {
  val c = LocalCc.current
  Text(title, color = c.text, fontSize = 42.sp, fontFamily = CcStyle.Serif, modifier = Modifier.padding(bottom = 6.dp))
  Text(body, color = c.sec, fontSize = 21.sp, lineHeight = 29.sp, modifier = Modifier.padding(bottom = 20.dp))
}

/** Mock `.grp`: rows on a soft panel, divided by hairlines. */
@Composable
internal fun CcSetGroup(content: @Composable ColumnScope.() -> Unit) {
  Column(Modifier.fillMaxWidth().padding(bottom = 22.dp).clip(RoundedCornerShape(26.dp)).background(LocalCc.current.surface2)
      .padding(horizontal = 24.dp, vertical = 4.dp), content = content)
}

/** Mock `.setRow`: title, optional grey line, trailing control; [divider] false on the last row. */
@Composable
internal fun CcSetRow(title: String, sub: String? = null, divider: Boolean = true, trailing: @Composable () -> Unit = {}) {
  val c = LocalCc.current
  Column {
    Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(title, color = c.text, fontSize = 22.sp)
        sub?.let { Text(it, color = c.sec, fontSize = 18.sp, lineHeight = 24.sp) }
      }
      trailing()
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(c.div))
  }
}

/** A toggle row writing [key]. */
@Composable
internal fun CcSetToggle(title: String, sub: String?, key: String, on: Boolean, write: (String, Any) -> Unit, divider: Boolean = true,
                         enabled: Boolean = true) {
  CcSetRow(title, sub, divider) { CcToggle(on, { write(key, it) }, enabled = enabled, label = title) }
}

/** − value + stepper (64 dp targets). */
@Composable
internal fun CcStepper(value: String, label: String, onMinus: () -> Unit, onPlus: () -> Unit, enabled: Boolean = true) {
  val c = LocalCc.current
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    CcRoundButton("minus", onMinus, "Less $label", size = 64, enabled = enabled)
    Text(value, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 84.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    CcRoundButton("plus", onPlus, "More $label", size = 64, enabled = enabled)
  }
}

/** A plain note under a group. */
@Composable
internal fun CcSetNote(text: String) {
  Text(text, color = LocalCc.current.sec, fontSize = 19.sp, lineHeight = 27.sp, modifier = Modifier.padding(bottom = 20.dp))
}
