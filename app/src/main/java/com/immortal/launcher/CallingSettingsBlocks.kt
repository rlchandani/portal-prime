/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.CallingSettings
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/** Settings › Calls › Ringtone (mock "Ringtone" list): tap a row to choose it, Play to hear it once. */
@Composable
internal fun CallRingtonePreviewBlock() {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  var selected by remember { mutableStateOf(CallingConfig.load(context).tone) }
  DisposableEffect(context) {
    val stop = CallingConfig.observe(context) { selected = CallingConfig.load(context).tone }
    onDispose { stop() }
  }
  Card {
    CallRingtone.entries.forEachIndexed { i, tone ->
      if (i > 0) Divider()
      Box(Modifier.callClick("Ringtone ${tone.label}", true) { CallingSettings.set(context, "ringtone", tone.wire) }) {
        SettingsInfoRow(tone.label, tone.help) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (tone == selected) CallIcon("check", p.accent, 34.dp)
            CallPill("Play", { CallRinger.preview(context, tone) }, icon = "vol", size = CallButtonSize.SM, surface = callsSurface())
          }
        }
      }
    }
  }
  FooterNote("Plays once at the ring volume. Calls never ring in quiet hours.")
}

/**
 * The drop-in grid (mock "Drop-ins": rows drop in on columns). This Portal owns and edits its
 * own column (who may drop in *here*); the other columns are what those rooms last announced,
 * shown read-only, because each Portal enforces its own rule.
 */
@Composable
internal fun CallDropInGridBlock() {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val peers by CallingStore.peers.collectAsState()
  var config by remember { mutableStateOf(CallingConfig.load(context)) }
  DisposableEffect(context) {
    val stop = CallingConfig.observe(context) { config = CallingConfig.load(context) }
    onDispose { stop() }
  }
  val self = remember { Calling.selfId(context).orEmpty() }
  val selfName = remember { Calling.selfName(context) }
  if (!config.dropInsOn) return
  val rooms = remember(peers) { peers.sortedBy { it.name.lowercase() } }
  Card {
    Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Tap a square in the $selfName column to change it. Rows drop in on columns.", color = p.secondary, fontSize = 21.sp)
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GridLabel("From ↓ · to →", header = true)
        GridLabel(selfName, header = true, mine = true)
        rooms.take(MAX_COLUMNS - 1).forEach { GridLabel(it.name, header = true) }
      }
      // This Portal's own row: what the other rooms allow from here (read-only, they own it).
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        GridLabel(selfName)
        GridSame()
        rooms.take(MAX_COLUMNS - 1).forEach { to -> GridCell(CallPolicy.dropInOn(to, self), editable = false) {} }
      }
      rooms.forEach { from ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
          GridLabel(from.name)
          GridCell(DropInGrid.policyFor(config.dropIns, from.id), editable = true) {
            val next = DropInGrid.encode(DropInGrid.cycle(config.dropIns, from.id))
            CallingSettings.set(context, "dropInRooms", next)
          }
          rooms.take(MAX_COLUMNS - 1).forEach { to ->
            if (to.id == from.id) GridSame() else GridCell(if (!to.dropInsOn) DropInPolicy.OFF
                else DropInGrid.policyFor(to.dropInPolicies, from.id), editable = false) {}
          }
        }
      }
      if (rooms.isEmpty()) Text("Other rooms appear here once their Portals answer.", color = p.secondary, fontSize = 21.sp)
    }
  }
  Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    RuleCard("Always a chime and banner", "3-second countdown with Not now. Never silent.", Modifier.weight(1f))
    RuleCard("Never from phones", "A phone can’t drop in. Only rooms in this home can.", Modifier.weight(1f))
  }
  Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    RuleCard("Never in quiet hours", "It becomes a normal ring request", Modifier.weight(1f))
    RuleCard("15 minutes max", "Then it ends unless someone taps Keep going", Modifier.weight(1f))
  }
}

@Composable
private fun GridLabel(text: String, header: Boolean = false, mine: Boolean = false) {
  val p = LocalApprovedPalette.current
  Box(Modifier.width(if (header && text.startsWith("From")) LABEL_W else if (header) CELL_W else LABEL_W).height(56.dp),
      contentAlignment = if (header) Alignment.Center else Alignment.CenterStart) {
    Text(text, color = if (mine) p.text else p.secondary, fontSize = if (header) 20.sp else 24.sp,
        fontWeight = if (mine || !header) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
        textAlign = if (header) TextAlign.Center else TextAlign.Start)
  }
}

@Composable
private fun GridCell(policy: DropInPolicy, editable: Boolean, onTap: () -> Unit) {
  val p = LocalApprovedPalette.current
  val shape = RoundedCornerShape(22.dp)
  val (fill, edge) = when (policy) {
    DropInPolicy.OFF -> p.surface2 to Color.Transparent
    DropInPolicy.ASK -> p.accent.copy(alpha = 0.14f) to p.accent.copy(alpha = 0.4f)
    DropInPolicy.CHIME -> CallColors.Ok.copy(alpha = 0.22f) to CallColors.Ok.copy(alpha = 0.5f)
  }
  Box(Modifier.width(CELL_W).height(80.dp).background(fill, shape).border(1.5.dp, edge, shape)
      .let { if (editable) it.callClick("Drop-ins: ${policy.label}", true, onTap) else it },
      contentAlignment = Alignment.Center) {
    Text(policy.label, color = if (editable) p.text else p.secondary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun GridSame() {
  Box(Modifier.width(CELL_W).height(80.dp), contentAlignment = Alignment.Center) {
    Text("—", color = LocalApprovedPalette.current.secondary, fontSize = 24.sp)
  }
}

@Composable
private fun RuleCard(title: String, body: String, modifier: Modifier) {
  val p = LocalApprovedPalette.current
  Column(modifier.background(p.surface, RoundedCornerShape(24.dp)).border(1.5.dp, p.outline, RoundedCornerShape(24.dp)).padding(24.dp)) {
    Text(title, color = p.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    Text(body, color = p.secondary, fontSize = 20.sp)
  }
}

/** Camera: what framing really does here, and the self view's locked rule. */
@Composable
internal fun CallCameraBlock() {
  Card {
    SettingsInfoRow("Camera framing", CallingCopy.FRAMING_NOTE) { LockedValue("Automatic") }
    Divider()
    SettingsInfoRow("If the camera is busy or the shutter is closed", "The call carries on with audio and tells you why.") {
      LockedValue("Audio")
    }
    Divider()
    SettingsInfoRow("Self view", "Always shown while your camera is on.") { LockedAlways() }
  }
}

/** Privacy guarantees: locked "Always" rows, not switches. */
@Composable
internal fun CallPrivacyBlock() {
  Card {
    PRIVACY_ROWS.forEachIndexed { i, (title, help) ->
      if (i > 0) Divider()
      SettingsInfoRow(title, help) { LockedAlways() }
    }
  }
  FooterNote("Calls are yours. This is exactly what happens, and none of it can be switched off.")
}

@Composable
private fun SettingsInfoRow(title: String, help: String, trailing: @Composable () -> Unit) {
  Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight).padding(horizontal = 28.dp, vertical = 22.dp),
      verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f).padding(end = 24.dp)) {
      Text(title, color = IosLabel, fontSize = Sizes.rowTitle, lineHeight = 34.sp)
      Text(help, color = IosSecondary, fontSize = Sizes.rowSub, lineHeight = 28.sp, modifier = Modifier.padding(top = 8.dp))
    }
    trailing()
  }
}

@Composable
private fun LockedAlways() = LockedValue("Always")

@Composable
private fun LockedValue(text: String) {
  val p = LocalApprovedPalette.current
  Row(Modifier.height(52.dp).background(p.surface2, RoundedCornerShape(50)).padding(horizontal = 20.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    CallIcon("lock", p.secondary, 24.dp)
    Text(text, color = p.secondary, fontSize = Sizes.rowValue, fontWeight = FontWeight.SemiBold)
  }
}

private val PRIVACY_ROWS = listOf(
    "Camera and mic open only after you answer" to "Before that, the other room sees this room’s name, nothing else",
    "Live bar on screen during every call" to "Red dot, “Camera on · Mic on”, who you are with",
    "Privacy shutter always wins" to "Shutter closed means camera off. We never work around it.",
    "No recording of calls" to "Nothing is saved: no video, no sound, no snapshots",
    "End-to-end encrypted" to "Video and sound are locked on this Portal and unlocked only on theirs, with this home’s key",
    "Camera closes the moment you leave" to "End, Back, Home or the screen going off close the camera and mic at once",
    "No Meta, no accounts, no ads" to "Nothing in Hearthpane Calling talks to Meta’s servers",
)
private val LABEL_W = 260.dp
private val CELL_W = 170.dp
private const val MAX_COLUMNS = 5
