/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The right-hand sheet (`.sheet`): the room, then a device inside it, as a back-stack in
 * [CcNav.sheets]. Slides in over a scrim (tap the scrim to close) with the mock's ease; the last
 * page stays drawn while it slides out.
 */
@Composable
internal fun CcSheetHost(s: CcUiState, ui: CcUi) {
  val c = LocalCc.current
  val top = ui.nav.topSheet
  val last = remember { mutableStateOf<CcSheet?>(null) }
  if (top != null) last.value = top
  val motion = LocalCcMotion.current
  val shown by animateFloatAsState(if (top != null) 1f else 0f, tween(if (motion) CC_SHEET_MS else 0, easing = CcEase), label = "sheet")
  val scrim by animateFloatAsState(if (top != null) 1f else 0f, tween(if (motion) CC_SCRIM_MS else 0), label = "sheetScrim")
  if (shown == 0f && top == null) return
  val page = top ?: last.value ?: return
  val widthPx = with(LocalDensity.current) { (CcStyle.SheetWidth + SHEET_INSET).toPx() }
  Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = scrim }.background(c.scrim)
        .then(if (top != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { ui.closeSheets() } else Modifier)
        .semantics { contentDescription = "Close" })
    Column(Modifier.align(Alignment.CenterEnd).padding(SHEET_INSET).width(CcStyle.SheetWidth).fillMaxHeight()
        .graphicsLayer { translationX = (1f - shown) * widthPx * SLIDE_OVERSHOOT }
        .shadow(if (c.dark) 40.dp else 24.dp, CcStyle.Sheet, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(CcStyle.Sheet).background(c.bg)
        .clickable(remember { MutableInteractionSource() }, indication = null) {}) {
      when (page) {
        is CcSheet.Room -> CcRoomSheet(s, ui, page.roomId)
        is CcSheet.Device -> CcDeviceSheet(s, ui, page.entityId)
      }
    }
  }
}

/** `.xbtn`: the round close button top-right of every sheet. */
@Composable
internal fun CcSheetClose(ui: CcUi) {
  val c = LocalCc.current
  Box(Modifier.size(72.dp).clip(CircleShape).background(c.surface2).clickable(role = Role.Button) { ui.closeSheets() }
      .semantics { contentDescription = "Close" }, contentAlignment = Alignment.Center) {
    CcIcon("x", c.text, 30.dp)
  }
}

/** `.back`: "‹ Living room" pill that pops the device page. */
@Composable
internal fun CcSheetBack(label: String, ui: CcUi) {
  val c = LocalCc.current
  Row(Modifier.height(64.dp).clip(CcStyle.Pill).background(c.surface2).clickable(role = Role.Button) { ui.back() }
      .padding(start = 12.dp, end = 22.dp).semantics { contentDescription = "Back to $label" },
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    CcIcon("chevL", c.text, 28.dp)
    Text(label, color = c.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

/** The scrolling `.shBody`. */
@Composable
internal fun ColumnScope.CcSheetBody(scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
  val state = rememberScrollState()
  Column(Modifier.weight(1f).fillMaxWidth().then(if (scroll) Modifier.verticalScroll(state) else Modifier)
      .padding(start = 34.dp, end = 34.dp, top = 6.dp, bottom = 34.dp), content = content)
}

/** Device page header: back pill + close, then the serif name with a busy ring. */
@Composable
internal fun CcDeviceHeader(s: CcUiState, ui: CcUi, e: CcEntity?, title: String) {
  val c = LocalCc.current
  val stack = ui.nav.sheets
  val prevRoom = stack.dropLast(1).filterIsInstance<CcSheet.Room>().lastOrNull()?.let { s.plan?.room(it.roomId)?.name }
  val backLabel = prevRoom ?: e?.let { s.plan?.roomOf(it.id)?.name }?.takeIf { stack.size > 1 } ?: "Back"
  Row(Modifier.fillMaxWidth().padding(start = 34.dp, end = 34.dp, top = 30.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
    CcSheetBack(backLabel, ui)
    Spacer(Modifier.weight(1f))
    CcSheetClose(ui)
  }
  Row(Modifier.padding(start = 34.dp, end = 34.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(title, color = c.text, fontSize = 50.sp, fontFamily = CcStyle.Serif, lineHeight = 54.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    CcCtlBusy(e != null && e.id in s.pending)
  }
}

/** A device page: header + the control for its kind. */
@Composable
private fun ColumnScope.CcDeviceSheet(s: CcUiState, ui: CcUi, entityId: String) {
  val e = s.entity(entityId)?.takeIf { !CcPolicy.hidden(it, s.settings) }
  CcDeviceHeader(s, ui, e, e?.name ?: "Not found")
  if (e == null) {
    Column(Modifier.padding(34.dp)) {
      CcCtlEmpty("warn", "This device isn't in Home Assistant", "It may have been renamed or removed. Edit the plan to place it again.")
    }
    return
  }
  CcDeviceControl(s, ui, e)
}

private val SHEET_INSET = 18.dp
/** `translateX(110%)` when hidden. */
private const val SLIDE_OVERSHOOT = 1.1f
