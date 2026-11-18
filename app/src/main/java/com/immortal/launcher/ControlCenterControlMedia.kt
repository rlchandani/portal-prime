/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Media page (`ctl-media`): now playing, transport (big centre button), volume, power. */
@Composable
internal fun CcMediaControl(ui: CcUi, e: CcEntity, gate: CcGate) {
  val c = LocalCc.current
  val off = e.state == "off" || e.state == "standby"
  val tv = !(e.deviceClass == "speaker" || "speaker" in e.id)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
    Box(Modifier.size(300.dp).clip(RoundedCornerShape(30.dp)).background(Brush.sweepGradient(if (tv) TV_ART else MUSIC_ART)))
    Column(Modifier.weight(1f)) {
      val source = listOfNotNull(if (tv) "TV" else "Speaker", e.attrs["app_name"] as? String ?: e.attrs["source"] as? String).joinToString(" · ")
      Text(source, color = c.sec, fontSize = 20.sp)
      Text(e.mediaTitle ?: if (off) "Off" else CcSheetLogic.pretty(e.state), color = c.text, fontSize = 38.sp, fontWeight = FontWeight.SemiBold,
          lineHeight = 44.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 6.dp))
      e.mediaArtist?.let { Text(it, color = c.sec, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
  }
  Row(Modifier.fillMaxWidth().padding(vertical = 34.dp), horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically) {
    val live = gate.enabled && !off
    Transport("prev", "Previous", 92, false, live) { ui.act(e, CcCalls.mediaPrevious(e)) }
    Transport(if (e.state == "playing") "pause" else "play", if (e.state == "playing") "Pause" else "Play", 120, true, live) {
      ui.act(e, CcCalls.mediaPlayPause(e))
    }
    Transport("next", "Next", 92, false, live) { ui.act(e, CcCalls.mediaNext(e)) }
  }
  e.volume?.let { v -> Volume(ui, e, v, gate.enabled && !off) }
  Box(Modifier.fillMaxWidth().padding(top = 26.dp), contentAlignment = Alignment.Center) {
    CcButton(if (off) "Turn on" else "Turn off", { ui.act(e, CcCalls.mediaPower(e, off)) }, icon = "power", enabled = gate.enabled)
  }
}

@Composable
private fun Transport(icon: String, label: String, size: Int, big: Boolean, enabled: Boolean, onClick: () -> Unit) {
  val c = LocalCc.current
  Box(Modifier.size(size.dp).alpha(if (enabled) 1f else CC_DISABLED_ALPHA).clip(CircleShape).background(if (big) c.text else c.surface2)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
      contentAlignment = Alignment.Center) {
    CcIcon(icon, if (big) c.bg else c.text, 40.dp)
  }
}

@Composable
private fun Volume(ui: CcUi, e: CcEntity, v: Float, enabled: Boolean) {
  val c = LocalCc.current
  var throttle by remember(e.id) { mutableStateOf(CcThrottle()) }
  fun push(p: Int, final: Boolean) {
    val now = SystemClock.uptimeMillis()
    if (!throttle.shouldSend(now, p, final)) return
    ControlCenter.send(ui.context, CcCalls.mediaVolume(e, p / PCT), undoable = final)
    throttle = throttle.sent(now, p)
  }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    CcIcon("vol", c.text, 32.dp)
    CcCtlHSlider(Math.round(v * PCT), { "Volume $it%" }, { push(it, true) }, Modifier.weight(1f), enabled = enabled,
        onLive = { push(it, false) }, description = "${e.name} volume")
  }
}

private const val PCT = 100f
private val TV_ART = listOf(Color(0xFF0F4C5C), Color(0xFF1F7A8C), Color(0xFFBFDBF7), Color(0xFFE1E5F2), Color(0xFF0F4C5C))
private val MUSIC_ART = listOf(Color(0xFF2A3F6B), Color(0xFF6A3E8F), Color(0xFFD06A6A), Color(0xFFF0B866), Color(0xFF2A3F6B))
