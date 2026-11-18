/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Left rail (`.rail`): back to Hearthpane Home, the six sections, Edit plan + Settings at the foot. */
@Composable
internal fun CcRail(s: CcUiState, ui: CcUi, onHome: () -> Unit) {
  val c = LocalCc.current
  val alerts = s.alerts().size
  Column(Modifier.width(CcStyle.RailWidth).fillMaxHeight().background(c.rail).padding(top = 22.dp, bottom = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Box(Modifier.size(72.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onHome)
        .semantics { contentDescription = "Back to Hearthpane home" }, contentAlignment = Alignment.Center) {
      CcIcon("home", c.text, 34.dp)
    }
    Spacer(Modifier.height(10.dp))
    RailItem("map", "Home", ui.nav.screen == CcScreen.MAP) { ui.go(CcScreen.MAP) }
    if (!CcPolicy.screenHidden(CcScreen.CAMERAS, s.settings)) RailItem("cam", "Cameras", ui.nav.screen == CcScreen.CAMERAS) { ui.go(CcScreen.CAMERAS) }
    RailItem("bolt", "Energy", ui.nav.screen == CcScreen.ENERGY) { ui.go(CcScreen.ENERGY) }
    RailItem("bell", "Alerts", ui.nav.screen == CcScreen.ALERTS, badge = alerts) { ui.go(CcScreen.ALERTS) }
    if (!CcPolicy.screenHidden(CcScreen.SECURITY, s.settings)) RailItem("shield", "Security", ui.nav.screen == CcScreen.SECURITY) { ui.go(CcScreen.SECURITY) }
    Spacer(Modifier.weight(1f))
    if (s.settings.kids) {
      RailItem("lock", "Leave kids", false) { ui.modal(CcModal.Pin(null, null, "Leave kids mode", "Enter the household PIN")) }
    } else {
      RailItem("edit", "Edit plan", ui.nav.screen == CcScreen.EDIT) { ui.go(CcScreen.EDIT) }
      RailItem("gear", "Settings", ui.nav.screen == CcScreen.SETTINGS) { ui.go(CcScreen.SETTINGS) }
    }
  }
}

@Composable
private fun RailItem(icon: String, label: String, on: Boolean, badge: Int = 0, onClick: () -> Unit) {
  val c = LocalCc.current
  Box(Modifier.size(104.dp, 96.dp)
      .then(if (on) Modifier.ccCard(RoundedCornerShape(24.dp)) else Modifier)
      .clip(RoundedCornerShape(24.dp)).clickable(role = Role.Tab, onClick = onClick)
      .semantics { contentDescription = if (badge > 0) "$label, $badge" else label }) {
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
      CcIcon(icon, if (on) c.text else c.sec, 38.dp)
      Text(label, color = if (on) c.text else c.sec, fontSize = 17.sp, maxLines = 1)
    }
    if (badge > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = (-22).dp, y = 12.dp).height(24.dp).widthIn(min = 24.dp)
        .clip(RoundedCornerShape(12.dp)).background(c.red).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
      Text("$badge", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
  }
}

/** Header (`.hdr`): ember glyph + serif title, then who's home, alarm, weather, live state, Ask Jarvis. */
@Composable
internal fun CcHeader(s: CcUiState, title: String, subtitle: String, ui: CcUi, onJarvis: () -> Unit) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth().height(CcStyle.HeaderHeight).padding(horizontal = 34.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      HearthpaneGlyph(30.dp)
      Column {
        Text(title, color = c.text, fontSize = 46.sp, fontFamily = CcStyle.Serif, maxLines = 1, lineHeight = 48.sp)
        Text(subtitle, color = c.sec, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    val home = s.people.filter { it.state == "home" }
    if (s.people.isNotEmpty()) CcPill(onClick = { ui.go(CcScreen.SECURITY) }) {
      CcAvatars(home.ifEmpty { s.people }.take(4))
      Text(if (home.isEmpty()) "Nobody home" else "${home.size} home", color = c.text, fontSize = 22.sp)
    }
    s.alarm?.let { a ->
      val armed = a.state.startsWith("armed")
      CcPill(onClick = { ui.go(CcScreen.SECURITY) }) {
        CcIcon(if (armed) "shieldck" else "shield", if (armed) c.green else c.text, 28.dp)
        Text(alarmLabel(a.state), color = if (armed) c.green else c.text, fontSize = 22.sp)
      }
    }
    s.weather?.let { w ->
      CcPill {
        CcIcon(if (isNight(s.now)) "moon" else "sun", c.text, 28.dp)
        val t = w.attrs["temperature"]?.toString()?.toDoubleOrNull()
        Text(listOfNotNull(t?.let { "${Math.round(it)}°" }, w.state.replace('-', ' ').replace('_', ' ').takeIf { it.isNotBlank() })
            .joinToString(" · "), color = c.text, fontSize = 22.sp)
      }
    }
    CcLivePill(s)
    Row(Modifier.height(72.dp).shadow(14.dp, CcStyle.Pill, ambientColor = CcStyle.Ember, spotColor = CcStyle.Ember)
        .clip(CcStyle.Pill).background(CcStyle.JarvisBrush).clickable(role = Role.Button, onClick = onJarvis)
        .padding(start = 18.dp, end = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Box(Modifier.size(40.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFFFF7EA), Color(0xFFFFD3A1), CcStyle.Ember))))
      Text("Ask Jarvis", color = CcStyle.EmberInk, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
    }
  }
}

/** "Live" / "Stale · 2 min" / "HA unreachable" pill with its status dot. */
@Composable
internal fun CcLivePill(s: CcUiState) {
  val c = LocalCc.current
  val (color, label) = when (s.connection.phase) {
    CcPhase.LIVE -> c.green to "Live"
    CcPhase.STALE -> c.amber to "Reconnecting"
    CcPhase.CONNECTING -> c.amber to "Connecting"
    CcPhase.UNREACHABLE -> c.red to "HA unreachable"
    CcPhase.AUTH_FAILED -> c.red to "Token expired"
    CcPhase.OFFLINE -> c.red to "Portal offline"
    CcPhase.NOT_CONFIGURED -> c.red to "Not set up"
  }
  CcPill {
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
      Box(Modifier.size(22.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)))
      Box(Modifier.size(12.dp).clip(CircleShape).background(color))
    }
    Text(label, color = c.text, fontSize = 22.sp)
  }
}

@Composable
internal fun CcPill(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
  Row(Modifier.height(60.dp).ccCard(CcStyle.Pill).clip(CcStyle.Pill)
      .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
      .padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

/** Overlapping initials (`.avs`). */
@Composable
internal fun CcAvatars(people: List<CcEntity>, size: Int = 36) {
  val c = LocalCc.current
  Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
    people.forEachIndexed { i, p ->
      Box(Modifier.size(size.dp).clip(CircleShape).background(c.surface).padding(3.dp).clip(CircleShape)
          .background(CcStyle.Avatars[i % CcStyle.Avatars.size]), contentAlignment = Alignment.Center) {
        Text(p.name.take(1).uppercase(), color = Color(0xFF14161B), fontSize = (size * 0.44f).sp, fontWeight = FontWeight.Bold)
      }
    }
  }
}

/** A `.card` surface: fill + (dark) hairline edge or (light) soft shadow. */
@Composable
internal fun Modifier.ccCard(shape: androidx.compose.ui.graphics.Shape = CcStyle.Card, color: Color? = null): Modifier {
  val c = LocalCc.current
  return if (c.dark) this.clip(shape).background(color ?: c.surface).border(1.dp, c.cardEdge, shape)
  else this.shadow(6.dp, shape, ambientColor = Color(0x2E182C39), spotColor = Color(0x2E182C39)).clip(shape).background(color ?: c.surface)
}

internal fun alarmLabel(state: String): String = when (state) {
  "disarmed" -> "Disarmed"
  "armed_home" -> "Armed · Home"
  "armed_away" -> "Armed · Away"
  "armed_night" -> "Armed · Night"
  "armed_vacation" -> "Armed · Vacation"
  "arming" -> "Arming…"
  "pending" -> "Entry delay"
  "triggered" -> "Triggered!"
  else -> state.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private const val NIGHT_START_H = 19
private const val NIGHT_END_H = 6
internal fun isNight(now: Long): Boolean {
  val h = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
  return h >= NIGHT_START_H || h < NIGHT_END_H
}

/** "Tuesday evening · 7:42 pm" (title line under Home). */
internal fun ccSubtitle(now: Long, extra: String? = null): String {
  val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
  val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(now))
  val part = when (cal.get(java.util.Calendar.HOUR_OF_DAY)) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night" }
  val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(now)).lowercase()
  return listOfNotNull("$day $part · $time", extra).joinToString(" · ")
}

internal fun ccTime(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms)).lowercase()
