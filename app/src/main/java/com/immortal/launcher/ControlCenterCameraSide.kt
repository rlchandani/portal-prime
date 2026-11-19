/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Back pill, camera name, how the picture arrives (honestly), Refresh, and the room's shortcuts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CcCameraSideHead(camera: CcEntity, ctx: CcCameraLogic.Context, canControl: Boolean, reason: String?, ui: CcUi, holder: CcFrameHolder) {
  val c = LocalCc.current
  Column(Modifier.fillMaxWidth().ccCard().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    BackPill { ui.camera(null) }
    Text(camera.name, color = c.text, fontSize = 34.sp, fontFamily = CcStyle.Serif, fontWeight = FontWeight.SemiBold, maxLines = 2)
    CcCameraHowItArrives(camera, ctx.roomName, canControl, ui, holder)
    if (ctx.shortcuts.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      ctx.shortcuts.forEach { e ->
        val call = CcCameraLogic.shortcutCall(e)
        ShortcutChip(CcIcons.forKind(CcKind.of(e)), CcCameraLogic.shortcutLabel(e), enabled = call != null && canControl) {
          call?.let { ui.act(e, it) }
        }
      }
    }
    reason?.takeIf { ctx.shortcuts.isNotEmpty() }?.let { Text(it, color = c.faint, fontSize = 17.sp) }
  }
}

/**
 * "Live video from Home Assistant", or — when HA only has stills — that in plain words, how old
 * the picture is, and a Refresh that asks HA (or ring-mqtt's take-snapshot button) for a new one.
 */
@Composable
private fun CcCameraHowItArrives(camera: CcEntity, room: String?, canControl: Boolean, ui: CcUi, holder: CcFrameHolder) {
  val c = LocalCc.current
  val info by rememberCamInfo(camera.id)
  val now by rememberCamNow()
  val watch = holder.watch
  val label = CcCamLiveness.label(watch, now)
  val line = CcCamLiveness.sourceLine(watch.source.takeIf { watch.hasFrame }, label.live, info.canStream, info.platform)
  Text(listOfNotNull(room, line).joinToString(" · "), color = c.sec, fontSize = 19.sp)
  if (label.live || info.canStream || !watch.hasFrame) return
  val age = label.stampMs?.let { "Picture taken ${CcCamLiveness.ago(now - it)}${if (label.captureKnown) "" else " (when we first saw it change)"}." }
      ?: "Picture unchanged for ${CcCamLiveness.duration(now - watch.imageSince)} — Home Assistant doesn't say when it was taken."
  Text(age, color = c.text, fontSize = 18.sp, lineHeight = 25.sp)
  Text(CcCamLiveness.whyNotLive(info.platform), color = c.faint, fontSize = 17.sp, lineHeight = 23.sp)
  var asked by remember(camera.id) { mutableStateOf<String?>(null) }
  ShortcutChip("refresh", "Refresh picture", enabled = canControl && asked != ASKING) {
    asked = ASKING
    ui.act(camera, info.refreshCall(camera.id)) { ok, _ -> asked = if (ok) ASKED else FAILED }
  }
  asked?.takeIf { it != ASKING }?.let { Text(it, color = c.faint, fontSize = 17.sp) }
}

private const val ASKING = "Asking…"
private const val ASKED = "Asked for a new picture · it appears here when the camera sends it."
private const val FAILED = "Home Assistant didn't take the request."

/** Recent activity next to the camera: its own state and the motion sensors in its room. */
@Composable
internal fun CcCameraRecent(camera: CcEntity, near: CcCameraLogic.Context, modifier: Modifier) {
  val c = LocalCc.current
  val motion = near.motion
  Column(modifier.ccCard().padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    CcSectionLabel("Recent")
    EventRow("cam", cameraState(camera), camera.lastChanged.takeIf { it > 0 }?.let { "since ${ccTime(it)}" } ?: "", highlighted = false)
    motion.forEach { m ->
      val on = m.state == "on"
      val title = if (on) "Motion · ${m.name}" else "No motion · ${m.name}"
      val time = m.lastChanged.takeIf { it > 0 }?.let { if (on) "since ${ccTime(it)}" else "last ${ccTime(it)}" } ?: ""
      EventRow("motion", title, time, highlighted = on)
    }
    if (motion.isEmpty()) Text("No motion sensor next to this camera. Put one in the same room on the plan to see activity here.",
        color = c.sec, fontSize = 18.sp, lineHeight = 25.sp)
  }
}

private fun cameraState(camera: CcEntity): String = when (camera.state) {
  "streaming" -> "Streaming"
  "recording" -> "Recording"
  "idle" -> "Camera idle"
  "unavailable", "unknown" -> "Camera unavailable"
  else -> camera.state.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun EventRow(icon: String, title: String, time: String, highlighted: Boolean) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).then(if (highlighted) Modifier.background(c.sel) else Modifier).padding(10.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Box(Modifier.size(96.dp, 60.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
      CcIcon(icon, if (highlighted) CcStyle.EmberLight else c.sec, 30.dp)
    }
    Column {
      Text(title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (time.isNotEmpty()) Text(time, color = c.sec, fontSize = 17.sp)
    }
  }
}

/** `.back`: chevron + "All cameras". */
@Composable
private fun BackPill(onClick: () -> Unit) {
  val c = LocalCc.current
  Row(Modifier.height(64.dp).clip(CcStyle.Pill).background(c.surface2).clickable(role = Role.Button, onClick = onClick)
      .padding(start = 12.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    CcIcon("chev", c.text, 28.dp, Modifier.rotate(HALF_TURN))
    Text("All cameras", color = c.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
  }
}

/** `.chip`: 56 tall in the mock; 64 here for the Portal's touch-target floor. */
@Composable
private fun ShortcutChip(icon: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
  val c = LocalCc.current
  Row(Modifier.height(CcStyle.MinTarget).alpha(if (enabled) 1f else CC_DISABLED_ALPHA).clip(CcStyle.Pill).background(c.surface2)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 22.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    CcIcon(icon, c.text, 26.dp)
    Text(label, color = c.text, fontSize = 20.sp, maxLines = 1)
  }
}

private const val HALF_TURN = 180f
