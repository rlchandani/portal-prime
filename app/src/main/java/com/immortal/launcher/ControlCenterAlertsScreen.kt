/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Alerts (`alerts`): Active / All / Resolved cards on the left, the Portal-wide banner policy on the right. */
@Composable
internal fun CcAlertsScreen(s: CcUiState, ui: CcUi) {
  // Only the Alerts slice reaches the body: an HA update that changes no card (or a switch back
  // to this screen with a newer snapshot) compares equal and the body and its cards skip.
  val st = s.settings
  CcAlertsBody(s.alertCards, st.warningAfterMin, st.bannersWarnings, st.bannersInfo, ui)
}

@Composable
private fun CcAlertsBody(cards: CcAlertCards, warnAfterMin: Int, warnings: Boolean, info: Boolean, ui: CcUi) {
  LaunchedEffect(cards.active) { CcAlertHistory.observe(cards.active, System.currentTimeMillis()) }
  val resolved by CcAlertHistory.resolved.collectAsState()
  var filter by rememberSaveable { mutableStateOf(CcAlertFilter.ACTIVE) }
  val onFilter = remember { { f: CcAlertFilter -> filter = f } }
  val count = cards.active.size
  val segs = remember(count) {
    listOf(Triple(CcAlertFilter.ACTIVE, "Active · $count", null), Triple(CcAlertFilter.ALL, "All", null),
        Triple(CcAlertFilter.RESOLVED, "Resolved", null))
  }
  val items = remember(filter, cards, resolved) { CcAlertRules.shown(filter, cards, resolved, ::ccTime) }
  val onOp = remember(ui) { { item: CcAlertItem -> runOp(ui, item) } }
  if (currentLayoutClass().compactPortrait) {
    // The Mini upright: one column; the Portal-wide banner policy follows the alerts.
    val set = remember(ui) { { key: String, v: Boolean -> com.immortal.launcher.settings.ControlCenterSettings.set(ui.context, key, v); Unit } }
    LazyColumn(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp), contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
      item(key = "seg") { Row { CcSeg(segs, filter, onFilter) } }
      if (items.isEmpty()) item(key = "clear") { AllClear(filter) }
      items(items, key = { "${it.alert.id}:${it.active}" }) { AlertCard(it, onOp) }
      item(key = "banners") {
        BannersCard(warnAfterMin, warnings, info, set, Modifier.fillMaxWidth().padding(top = 10.dp))
      }
      item(key = "preview") { BannerPreviewCard(Modifier.fillMaxWidth()) }
    }
    return
  }
  Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      item(key = "seg") { Row { CcSeg(segs, filter, onFilter) } }
      if (items.isEmpty()) item(key = "clear") { AllClear(filter) }
      items(items, key = { "${it.alert.id}:${it.active}" }) { AlertCard(it, onOp) }
    }
    val set = remember(ui) { { key: String, v: Boolean -> com.immortal.launcher.settings.ControlCenterSettings.set(ui.context, key, v); Unit } }
    Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
      BannersCard(warnAfterMin, warnings, info, set, Modifier.fillMaxWidth())
      BannerPreviewCard(Modifier.fillMaxWidth())
    }
  }
}

private fun runOp(ui: CcUi, item: CcAlertItem) {
  when (val op = item.op) {
    is CcAlertOp.Act -> ui.act(op.entity, op.call)
    is CcAlertOp.OpenRoom -> { ui.go(CcScreen.MAP); ui.openRoom(op.roomId) }
    is CcAlertOp.Dismiss -> { CcAlertHistory.dismissed(item, System.currentTimeMillis()); ControlCenter.dismissAlert(ui.context, item.alert, op.until) }
    null -> Unit
  }
}

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
private fun AlertCard(item: CcAlertItem, onOp: (CcAlertItem) -> Unit) {
  val c = LocalCc.current
  val a = item.alert
  val tone = if (item.active) CcAlertRules.tone(a.severity) else CcTone.GREEN
  val (bg, fg) = when (tone) {
    CcTone.RED -> c.redBg to c.red; CcTone.AMBER -> c.amberBg to c.amber; CcTone.GREEN -> c.greenBg to c.green; CcTone.NEUTRAL -> c.sel to c.text
  }
  Row(Modifier.fillMaxWidth().alpha(if (item.active) 1f else RESOLVED_ALPHA).ccCard(RoundedCornerShape(30.dp)).padding(horizontal = 24.dp, vertical = 22.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    // A filled shape, not a clip: no extra render node per card, the same pixels.
    Box(Modifier.size(76.dp).background(bg, ICON_TILE_SHAPE), contentAlignment = Alignment.Center) {
      CcIcon(item.icon, fg, 40.dp)
    }
    Column(Modifier.weight(1f)) {
      Text(a.title, color = c.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(item.line, color = c.sec, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (!item.active) CcTag("Resolved", CcTone.GREEN)
    else item.label?.let { label ->
      val click = remember(item, onOp) { { onOp(item) } }
      SmallButton(label, item.spoken ?: CcSpeech.alertAction(a.title, label), item.danger, item.enabled, click)
    }
  }
}

private const val RESOLVED_ALPHA = 0.6f
private val ICON_TILE_SHAPE = RoundedCornerShape(22.dp)

/** `.btn.sm.pri` (or `.danger` for critical): 64 tall here for the touch floor. */
@Composable
private fun SmallButton(label: String, spoken: String, danger: Boolean, enabled: Boolean, onClick: () -> Unit) {
  val c = LocalCc.current
  val (bg, fg) = if (danger) c.red to Color.White else c.accent to c.accentInk
  Box(Modifier.height(CcStyle.MinTarget).alpha(if (enabled) 1f else CC_DISABLED_ALPHA).clip(CcStyle.Pill).background(bg)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick).ccSpeak(spoken).padding(horizontal = 24.dp),
      contentAlignment = Alignment.Center) {
    Text(label, color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

/** PORTAL-WIDE BANNERS: critical always; warnings and info switch for this household. */
@Composable
private fun BannersCard(warnAfterMin: Int, warnings: Boolean, info: Boolean, set: (String, Boolean) -> Unit, modifier: Modifier) {
  val c = LocalCc.current
  Column(modifier.ccCard().padding(horizontal = 28.dp, vertical = 24.dp)) {
    CcSectionLabel("Portal-wide banners", Modifier.padding(bottom = 14.dp))
    BannerRow("Critical — leak, smoke, CO", "Every Portal, full screen with sound, even kids & do-not-disturb", divider = true) { CcTag("Always", CcTone.RED) }
    BannerRow("Warnings — garage, doors, windows", "Full Portals · after $warnAfterMin min", divider = true) {
      CcToggle(warnings, { set("bannersWarnings", it) }, label = "Warning banners")
    }
    BannerRow("Info — washer, dryer, dishwasher", "Full Portals · never in quiet hours", divider = true) {
      CcToggle(info, { set("bannersInfo", it) }, label = "Info banners")
    }
    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      CcIcon("moon", c.sec, 24.dp)
      Text("Quiet 10 pm–8 am · from Settings › Sounds", color = c.sec, fontSize = 18.sp)
    }
  }
}

/** PREVIEW A BANNER ON PORTAL HOME: one chip per severity; silent, never touches Home Assistant. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BannerPreviewCard(modifier: Modifier) {
  // The Control Center's window: the preview's fallback when the accessibility overlay host isn't bound.
  val activity = LocalContext.current.ccActivity()
  Column(modifier.ccCard().padding(horizontal = 28.dp, vertical = 24.dp)) {
    CcSectionLabel("Preview a banner on Portal home", Modifier.padding(bottom = 14.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      CcBannerPreview.samples.forEach { sample ->
        CcButton(sample.label, { ControlCenterBanners.preview(activity, sample.alert) }, icon = sample.icon, height = PREVIEW_CHIP_HEIGHT)
      }
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

private const val PREVIEW_CHIP_HEIGHT = 60

private tailrec fun Context.ccActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> if (baseContext === this) null else baseContext.ccActivity()
  else -> null
}
