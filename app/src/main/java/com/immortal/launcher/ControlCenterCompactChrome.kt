/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Control Center chrome for the Mini upright ([LayoutClass.COMPACT_PORTRAIT]): a two-row header
 * (title + Ask Jarvis, then the status pills, which scroll sideways if a home has many) and a
 * bottom navigation bar in the rail's colours instead of the left rail. Sizes are in the CC's
 * scaled units ([CcCompactScale.PORTRAIT]).
 */
internal object CcCompactChrome {
  val HeaderHeight = 186.dp
  val NavHeight = 116.dp
}

/** Portrait header: glyph + serif title / subtitle + Ask Jarvis; below it who's home, alarm, weather, live. */
@Composable
internal fun CcCompactHeader(h: CcHeaderSlice, ui: CcUi, onJarvis: () -> Unit) {
  val c = LocalCc.current
  Column(Modifier.fillMaxWidth().height(CcCompactChrome.HeaderHeight).ccRegionLayer(c.bg)
      .padding(start = 30.dp, end = 30.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.fillMaxWidth().height(84.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      HearthpaneGlyph(30.dp)
      Column(Modifier.weight(1f).ccSpeak("${h.title}. ${h.subtitle}", heading = true)) {
        Text(h.title, color = c.text, fontSize = 42.sp, fontFamily = CcStyle.Serif, maxLines = 1, overflow = TextOverflow.Ellipsis,
            lineHeight = 46.sp)
        Text(h.subtitle, color = c.sec, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      CompactJarvis(enabled = !h.phase.blocksHome, onJarvis)
    }
    Row(Modifier.fillMaxWidth().height(64.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      HeaderPills(h, ui)
    }
  }
}

@Composable
private fun HeaderPills(h: CcHeaderSlice, ui: CcUi) {
  val c = LocalCc.current
  CcLivePill(h.phase)
  val home = h.people.filter { it.state == "home" }
  if (h.people.isNotEmpty()) CcPill(CcSpeech.people(home.map { it.name }, h.people.size), onClick = { ui.go(CcScreen.SECURITY) }) {
    CcAvatars(home.ifEmpty { h.people }.take(4))
    Text(if (home.isEmpty()) "Nobody home" else "${home.size} home", color = c.text, fontSize = 22.sp, maxLines = 1, softWrap = false)
  }
  h.alarm?.let { a ->
    val armed = a.state.startsWith("armed")
    CcPill(CcSpeech.alarm(alarmLabel(a.state)), onClick = { ui.go(CcScreen.SECURITY) }) {
      CcIcon(if (armed) "shieldck" else "shield", if (armed) c.green else c.text, 28.dp)
      Text(alarmLabel(a.state), color = if (armed) c.green else c.text, fontSize = 22.sp, maxLines = 1, softWrap = false)
    }
  }
  if (h.weatherTemp != null || h.weatherText != null) {
    val weather = listOfNotNull(h.weatherTemp?.let { "$it°" }, h.weatherText).joinToString(" · ")
    CcPill("Weather $weather") {
      CcIcon(if (h.night) "moon" else "sun", c.text, 28.dp)
      Text(weather, color = c.text, fontSize = 22.sp, maxLines = 1, softWrap = false)
    }
  }
  h.debug?.let { label ->
    CcPill(label) { CcIcon("warn", c.amber, 26.dp); Text(label, color = c.amber, fontSize = 22.sp, maxLines = 1, softWrap = false) }
  }
}

@Composable
private fun CompactJarvis(enabled: Boolean, onJarvis: () -> Unit) {
  Row(Modifier.height(72.dp).alpha(if (enabled) 1f else CC_DISABLED_ALPHA)
      .then(if (enabled) Modifier.shadow(14.dp, CcStyle.Pill, ambientColor = CcStyle.Ember, spotColor = CcStyle.Ember) else Modifier)
      .clip(CcStyle.Pill).background(CcStyle.JarvisBrush).clickable(enabled = enabled, role = Role.Button, onClick = onJarvis).ccSpeak("Ask Jarvis")
      .padding(start = 16.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFFFF7EA), Color(0xFFFFD3A1), CcStyle.Ember))))
    Text("Ask Jarvis", color = CcStyle.EmberInk, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
  }
}

/** Portrait bottom bar: back to Hearthpane, the sections, then Edit plan + Settings (or Leave kids). */
@Composable
internal fun CcCompactNavBar(r: CcRailSlice, ui: CcUi, onHome: () -> Unit) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth().height(CcCompactChrome.NavHeight).ccRegionLayer(c.rail).padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Box(Modifier.size(72.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onHome)
        .semantics { contentDescription = "Back to Hearthpane home" }, contentAlignment = Alignment.Center) {
      CcIcon("home", c.text, 34.dp)
    }
    val open = !r.blocked
    NavItem("map", "Home", r.screen == CcScreen.MAP) { ui.go(CcScreen.MAP) }
    if (!r.hideCameras) NavItem("cam", "Cameras", r.screen == CcScreen.CAMERAS, enabled = open) { ui.go(CcScreen.CAMERAS) }
    NavItem("car", r.car, r.screen == CcScreen.TESLA, enabled = open) { ui.go(CcScreen.TESLA) }
    NavItem("bell", "Alerts", r.screen == CcScreen.ALERTS, badge = r.alerts, enabled = open) { ui.go(CcScreen.ALERTS) }
    if (!r.hideSecurity) NavItem("shield", "Security", r.screen == CcScreen.SECURITY, enabled = open) { ui.go(CcScreen.SECURITY) }
    if (r.kids) {
      NavItem("lock", "Leave kids", false) { ui.modal(CcModal.Pin(null, null, "Leave kids mode", "Enter the household PIN")) }
    } else {
      NavItem("edit", "Edit plan", r.screen == CcScreen.EDIT, enabled = open) { ui.go(CcScreen.EDIT) }
      NavItem("gear", "Settings", r.screen == CcScreen.SETTINGS) { ui.go(CcScreen.SETTINGS) }
    }
  }
}

@Composable
private fun RowScope.NavItem(icon: String, label: String, on: Boolean, badge: Int = 0, enabled: Boolean = true, onClick: () -> Unit) {
  val c = LocalCc.current
  Box(Modifier.weight(1f).fillMaxHeight().alpha(if (enabled) 1f else CC_DISABLED_ALPHA)
      .then(if (on) Modifier.ccCard(NAV_ITEM) else Modifier)
      .clip(NAV_ITEM).clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
      .ccSpeak(if (badge > 0) "$label, $badge" else label, selected = on)) {
    Column(Modifier.align(Alignment.Center).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
      CcIcon(icon, if (on) c.text else c.sec, 36.dp)
      Text(label, color = if (on) c.text else c.sec, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (badge > 0) Box(Modifier.align(Alignment.TopCenter).offset(x = 22.dp, y = 6.dp).height(24.dp).widthIn(min = 24.dp)
        .clip(RoundedCornerShape(12.dp)).background(c.red).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
      Text("$badge", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
  }
}

private val NAV_ITEM = RoundedCornerShape(24.dp)
