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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Alerts (`alerts`): Active / All / Resolved cards on the left, the Portal-wide banner policy on the right. */
@Composable
internal fun CcAlertsScreen(s: CcUiState, ui: CcUi) {
  val active = remember(s.entities, s.dismissed, s.appliancesDone, s.settings, s.now / ALERT_TICK_MS) { s.alerts() }
  LaunchedEffect(active) { CcAlertHistory.observe(active, System.currentTimeMillis()) }
  val resolved by CcAlertHistory.resolved.collectAsState()
  var filter by rememberSaveable { mutableStateOf(CcAlertFilter.ACTIVE) }
  val list = remember(filter, active, resolved) { CcAlertRules.list(filter, active, resolved) }
  Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      item {
        Row {
          CcSeg(listOf(Triple(CcAlertFilter.ACTIVE, "Active · ${active.size}", null), Triple(CcAlertFilter.ALL, "All", null),
              Triple(CcAlertFilter.RESOLVED, "Resolved", null)), filter, { filter = it })
        }
      }
      if (list.isEmpty()) item { AllClear(filter) }
      items(list, key = { (a, on) -> "${a.id}:$on" }) { (a, on) -> AlertCard(s, ui, a, on) }
    }
    BannersCard(s, ui, Modifier.width(520.dp))
  }
}

/** Alerts re-derive at least once a minute so "Open 17 min" keeps counting. */
private const val ALERT_TICK_MS = 60_000L

@Composable
private fun AllClear(filter: CcAlertFilter) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth().ccCard(RoundedCornerShape(30.dp)).padding(40.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
    CcIcon("check", c.green, 34.dp)
    Column {
      Text(if (filter == CcAlertFilter.RESOLVED) "Nothing resolved yet" else "All clear", color = c.text, fontSize = 30.sp, fontFamily = CcStyle.Serif)
      Text(if (filter == CcAlertFilter.RESOLVED) "Alerts you dismiss or that clear up this session appear here."
      else "Nothing needs you right now.", color = c.sec, fontSize = 22.sp)
    }
  }
}

/** `.alCard`: severity icon tile, title + body · time, one action. Resolved cards are dimmed. */
@Composable
private fun AlertCard(s: CcUiState, ui: CcUi, a: CcAlert, active: Boolean) {
  val c = LocalCc.current
  val entity = s.entity(a.entityId)
  val tone = if (active) CcAlertRules.tone(a.severity) else CcTone.GREEN
  val (bg, fg) = when (tone) {
    CcTone.RED -> c.redBg to c.red; CcTone.AMBER -> c.amberBg to c.amber; CcTone.GREEN -> c.greenBg to c.green; CcTone.NEUTRAL -> c.sel to c.text
  }
  Row(Modifier.fillMaxWidth().alpha(if (active) 1f else RESOLVED_ALPHA).ccCard(RoundedCornerShape(30.dp)).padding(horizontal = 24.dp, vertical = 22.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    Box(Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(bg), contentAlignment = Alignment.Center) {
      CcIcon(CcAlertRules.icon(a, entity), fg, 40.dp)
    }
    Column(Modifier.weight(1f)) {
      Text(a.title, color = c.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(listOfNotNull(a.body, a.since.takeIf { it > 0 }?.let(::ccTime)).joinToString(" · "), color = c.sec, fontSize = 20.sp,
          maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (active) AlertAction(s, ui, a) else CcTag("Resolved", CcTone.GREEN)
  }
}

private const val RESOLVED_ALPHA = 0.6f

@Composable
private fun AlertAction(s: CcUiState, ui: CcUi, a: CcAlert) {
  val now = System.currentTimeMillis()
  val label = CcAlertRules.label(a.action, now)
  val critical = a.severity == CcSeverity.CRITICAL
  val dismiss = { until: Long -> CcAlertHistory.dismissed(a, now); ControlCenter.dismissAlert(ui.context, a, until) }
  when (a.action) {
    CcAlertAction.CLOSE -> CcAlertRules.garageFor(a, s.entities)?.takeIf { it.isOpen }?.let { g ->
      SmallButton(label, critical, s.connection.canControl) { ui.act(g, CcCalls.coverClose(g)) }
    }
    CcAlertAction.SHUT_WATER -> remember(s.entities, s.plan) { CcAlertRules.valveFor(a, s.entities.values) { id -> s.plan?.roomOf(id)?.id } }
        ?.takeIf { it.isOpen || it.isOn }?.let { v -> SmallButton(label, critical, s.connection.canControl) { ui.act(v, CcAlertRules.shutCall(v)) } }
    CcAlertAction.VIEW -> s.plan?.roomOf(a.entityId)?.let { r -> SmallButton(label, critical, true) { ui.go(CcScreen.MAP); ui.openRoom(r.id) } }
    CcAlertAction.DISMISS, CcAlertAction.SNOOZE, CcAlertAction.REMIND ->
      CcAlertRules.until(a.action, now)?.let { u -> SmallButton(label, critical, true) { dismiss(u) } }
  }
}

/** `.btn.sm.pri` (or `.danger` for critical): 64 tall here for the touch floor. */
@Composable
private fun SmallButton(label: String, danger: Boolean, enabled: Boolean, onClick: () -> Unit) {
  val c = LocalCc.current
  val (bg, fg) = if (danger) c.red to Color.White else c.accent to c.accentInk
  Box(Modifier.height(CcStyle.MinTarget).alpha(if (enabled) 1f else CC_DISABLED_ALPHA).clip(CcStyle.Pill).background(bg)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
    Text(label, color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

/** PORTAL-WIDE BANNERS: critical always; warnings and info switch for this household. */
@Composable
private fun BannersCard(s: CcUiState, ui: CcUi, modifier: Modifier) {
  val c = LocalCc.current
  val set = { key: String, v: Boolean -> com.immortal.launcher.settings.ControlCenterSettings.set(ui.context, key, v); Unit }
  Column(modifier.ccCard().padding(horizontal = 28.dp, vertical = 24.dp)) {
    CcSectionLabel("Portal-wide banners", Modifier.padding(bottom = 14.dp))
    BannerRow("Critical — leak, smoke, CO", "Every Portal, full screen with sound, even kids & do-not-disturb", divider = true) { CcTag("Always", CcTone.RED) }
    BannerRow("Warnings — garage, doors, windows", "Full Portals · after ${s.settings.warningAfterMin} min", divider = true) {
      CcToggle(s.settings.bannersWarnings, { set("bannersWarnings", it) }, label = "Warning banners")
    }
    BannerRow("Info — washer, dryer, dishwasher", "Full Portals · never in quiet hours", divider = true) {
      CcToggle(s.settings.bannersInfo, { set("bannersInfo", it) }, label = "Info banners")
    }
    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      CcIcon("moon", c.sec, 24.dp)
      Text("Quiet 10 pm–8 am · from Settings › Sounds", color = c.sec, fontSize = 18.sp)
    }
  }
}

/** `.setRow`: title + grey line, the control on the right, a hairline under it. */
@Composable
private fun BannerRow(title: String, sub: String, divider: Boolean, control: @Composable () -> Unit) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    Column(Modifier.weight(1f)) {
      Text(title, color = c.text, fontSize = 22.sp)
      Text(sub, color = c.sec, fontSize = 18.sp)
    }
    control()
  }
  if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(c.div))
}
