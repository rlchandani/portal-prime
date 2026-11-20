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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the rail shows; equal slices skip the rail entirely on HA updates. */
@Immutable
internal data class CcRailSlice(val screen: CcScreen, val alerts: Int, val kids: Boolean, val hideCameras: Boolean, val hideSecurity: Boolean,
                                  val car: String = CcTeslaIds.DEFAULT_LABEL,
                                  /** Home Assistant is down ([CcPhase.blocksHome]): only Home and Settings stay open. */
                                  val blocked: Boolean = false) {
  companion object {
    fun of(s: CcUiState, screen: CcScreen) = CcRailSlice(screen, s.alerts().size, s.settings.kids,
        CcPolicy.screenHidden(CcScreen.CAMERAS, s.settings), CcPolicy.screenHidden(CcScreen.SECURITY, s.settings),
        s.registry?.tesla?.label ?: CcTeslaIds.DEFAULT_LABEL, s.connection.phase.blocksHome)
  }
}

/** Left rail (`.rail`): back to Hearthpane Home, the six sections, Edit plan + Settings at the foot. */
@Composable
internal fun CcRail(r: CcRailSlice, ui: CcUi, onHome: () -> Unit) {
  val c = LocalCc.current
  val alerts = r.alerts
  Column(Modifier.width(CcStyle.RailWidth).fillMaxHeight().ccRegionLayer(c.rail).padding(top = 22.dp, bottom = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Box(Modifier.size(72.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onHome)
        .semantics { contentDescription = "Back to Hearthpane home" }, contentAlignment = Alignment.Center) {
      CcIcon("home", c.text, 34.dp)
    }
    Spacer(Modifier.height(10.dp))
    val open = !r.blocked
    RailItem("map", "Home", r.screen == CcScreen.MAP) { ui.go(CcScreen.MAP) }
    if (!r.hideCameras) RailItem("cam", "Cameras", r.screen == CcScreen.CAMERAS, enabled = open) { ui.go(CcScreen.CAMERAS) }
    RailItem("car", r.car, r.screen == CcScreen.TESLA, enabled = open) { ui.go(CcScreen.TESLA) }
    RailItem("bell", "Alerts", r.screen == CcScreen.ALERTS, badge = alerts, enabled = open) { ui.go(CcScreen.ALERTS) }
    if (!r.hideSecurity) RailItem("shield", "Security", r.screen == CcScreen.SECURITY, enabled = open) { ui.go(CcScreen.SECURITY) }
    Spacer(Modifier.weight(1f))
    if (r.kids) {
      RailItem("lock", "Leave kids", false) { ui.modal(CcModal.Pin(null, null, "Leave kids mode", "Enter the household PIN")) }
    } else {
      RailItem("edit", "Edit plan", r.screen == CcScreen.EDIT, enabled = open) { ui.go(CcScreen.EDIT) }
      RailItem("gear", "Settings", r.screen == CcScreen.SETTINGS) { ui.go(CcScreen.SETTINGS) }
    }
  }
}

@Composable
private fun RailItem(icon: String, label: String, on: Boolean, badge: Int = 0, enabled: Boolean = true, onClick: () -> Unit) {
  val c = LocalCc.current
  Box(Modifier.size(104.dp, 96.dp).alpha(if (enabled) 1f else CC_DISABLED_ALPHA)
      .then(if (on) Modifier.ccCard(RoundedCornerShape(24.dp)) else Modifier)
      .clip(RoundedCornerShape(24.dp)).clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
      .ccSpeak(if (badge > 0) "$label, $badge" else label, selected = on)) {
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

/** What the header shows (people, alarm, weather, connection, debug pill) — skips when equal. */
@Immutable
internal data class CcHeaderSlice(
    val title: String, val subtitle: String, val people: List<CcEntity>, val alarm: CcEntity?,
    val weatherTemp: Long?, val weatherText: String?, val night: Boolean, val phase: CcPhase, val debug: String?,
) {
  companion object {
    fun of(s: CcUiState, title: String, subtitle: String): CcHeaderSlice {
      val w = s.weather
      return CcHeaderSlice(title, subtitle, s.people, s.alarm,
          w?.attrs?.get("temperature")?.toString()?.toDoubleOrNull()?.let { Math.round(it) },
          w?.state?.replace('-', ' ')?.replace('_', ' ')?.takeIf { it.isNotBlank() }, isNight(s.now), s.connection.phase, s.debug)
    }
  }
}

/** Header (`.hdr`): ember glyph + serif title, then who's home, alarm, weather, live state, Ask Jarvis. */
@Composable
internal fun CcHeader(h: CcHeaderSlice, ui: CcUi, onJarvis: () -> Unit) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth().height(CcStyle.HeaderHeight).ccRegionLayer(c.bg).padding(horizontal = 34.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      HearthpaneGlyph(30.dp)
      Column(Modifier.ccSpeak("${h.title}. ${h.subtitle}", heading = true)) {
        Text(h.title, color = c.text, fontSize = 46.sp, fontFamily = CcStyle.Serif, maxLines = 1, lineHeight = 48.sp)
        Text(h.subtitle, color = c.sec, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    val home = h.people.filter { it.state == "home" }
    if (h.people.isNotEmpty()) CcPill(CcSpeech.people(home.map { it.name }, h.people.size), onClick = { ui.go(CcScreen.SECURITY) }) {
      CcAvatars(home.ifEmpty { h.people }.take(4))
      Text(if (home.isEmpty()) "Nobody home" else "${home.size} home", color = c.text, fontSize = 22.sp)
    }
    h.alarm?.let { a ->
      val armed = a.state.startsWith("armed")
      CcPill(CcSpeech.alarm(alarmLabel(a.state)), onClick = { ui.go(CcScreen.SECURITY) }) {
        CcIcon(if (armed) "shieldck" else "shield", if (armed) c.green else c.text, 28.dp)
        Text(alarmLabel(a.state), color = if (armed) c.green else c.text, fontSize = 22.sp)
      }
    }
    if (h.weatherTemp != null || h.weatherText != null) {
      val weather = listOfNotNull(h.weatherTemp?.let { "$it°" }, h.weatherText).joinToString(" · ")
      CcPill("Weather $weather") {
        CcIcon(if (h.night) "moon" else "sun", c.text, 28.dp)
        Text(weather, color = c.text, fontSize = 22.sp)
      }
    }
    h.debug?.let { label -> CcPill(label) { CcIcon("warn", c.amber, 26.dp); Text(label, color = c.amber, fontSize = 22.sp) } }
    CcLivePill(h.phase)
    val jarvis = !h.phase.blocksHome
    Row(Modifier.height(72.dp).alpha(if (jarvis) 1f else CC_DISABLED_ALPHA)
        .then(if (jarvis) Modifier.shadow(14.dp, CcStyle.Pill, ambientColor = CcStyle.Ember, spotColor = CcStyle.Ember) else Modifier)
        .clip(CcStyle.Pill).background(CcStyle.JarvisBrush).clickable(enabled = jarvis, role = Role.Button, onClick = onJarvis).ccSpeak("Ask Jarvis")
        .padding(start = 18.dp, end = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Box(Modifier.size(40.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFFFF7EA), Color(0xFFFFD3A1), CcStyle.Ember))))
      Text("Ask Jarvis", color = CcStyle.EmberInk, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
    }
  }
}

/** "Live" / "Stale · 2 min" / "HA unreachable" pill with its status dot. */
@Composable
internal fun CcLivePill(phase: CcPhase) {
  val c = LocalCc.current
  val (color, label) = when (phase) {
    CcPhase.LIVE -> c.green to "Live"
    CcPhase.STALE -> c.amber to "Reconnecting"
    CcPhase.CONNECTING -> c.amber to "Connecting"
    CcPhase.UNREACHABLE -> c.red to "HA unreachable"
    CcPhase.AUTH_FAILED -> c.red to "Token expired"
    CcPhase.OFFLINE -> c.red to "Portal offline"
    CcPhase.NOT_CONFIGURED -> c.red to "Not set up"
  }
  CcPill("Home Assistant: $label") {
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
      Box(Modifier.size(22.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)))
      Box(Modifier.size(12.dp).clip(CircleShape).background(color))
    }
    Text(label, color = c.text, fontSize = 22.sp)
  }
}

/** A header pill: one screen-reader node saying [description]. */
@Composable
internal fun CcPill(description: String, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
  Row(Modifier.height(60.dp).ccCard(CcStyle.Pill).clip(CcStyle.Pill)
      .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).ccSpeak(description)
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
  // Each card is its own render layer: a change inside one card re-records only that card.
  return if (c.dark) this.graphicsLayer().clip(shape).background(color ?: c.surface).border(1.dp, c.cardEdge, shape)
  else this.graphicsLayer().shadow(6.dp, shape, ambientColor = Color(0x2E182C39), spotColor = Color(0x2E182C39)).clip(shape).background(color ?: c.surface)
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
