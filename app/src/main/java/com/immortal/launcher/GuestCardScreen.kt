/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val IDLE_CLOSE_MS = 10 * 60 * 1000L
private const val QR_PX = 720

@Composable
internal fun GuestCardScreen(
    content: GuestCardContent?,
    mode: GuestModeState,
    onClose: () -> Unit,
    onStart: (GuestSchedule) -> Unit,
    onEnd: () -> Unit,
    onEdit: () -> Unit,
) {
  var touchedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(touchedAt) { delay(IDLE_CLOSE_MS); onClose() }
  Box(Modifier.fillMaxSize().background(ApprovedHomePalette.Background).pointerInput(Unit) {
    awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); touchedAt = System.currentTimeMillis() } }
  }.padding(horizontal = 48.dp, vertical = 32.dp)) {
    when {
      content == null -> Unit
      !content.ready -> GuestCardEmpty(onEdit)
      else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp),
          verticalAlignment = Alignment.CenterVertically) {
        GuestQr(content.network)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
          GuestWifiDetails(content)
          GuestBasics(content.settings)
          GuestModeControls(mode, onStart, onEnd)
        }
      }
    }
    Box(Modifier.align(Alignment.TopEnd)) { RoundIconButton("x", "Close guest card", onClose) }
  }
}

@Composable
private fun GuestCardEmpty(onEdit: () -> Unit) {
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally) {
    Text("The guest card isn't set up yet", color = ApprovedHomePalette.Text, fontSize = 40.sp, lineHeight = 50.sp,
        modifier = Modifier.semantics { heading() })
    Text("Add your Wi-Fi network and password, and a few house basics.", color = ApprovedHomePalette.Secondary,
        fontSize = 24.sp, lineHeight = 34.sp)
    ToolsButton("Set up guest card", primary = true, onClick = onEdit)
  }
}

@Composable
private fun GuestQr(network: GuestWifi.Network) {
  val payload = remember(network.ssid, network.password, network.security, network.hidden) { GuestWifi.payload(network) }
  val qr by produceState<Bitmap?>(null, payload) {
    value = payload?.let { withContext(Dispatchers.Default) { lanSetupQr(it, QR_PX) } }
  }
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(Modifier.size(400.dp).clip(RoundedCornerShape(28.dp)).background(Color.White).approvedOutline(RoundedCornerShape(28.dp))
        .padding(20.dp), contentAlignment = Alignment.Center) {
      qr?.let {
        Image(it.asImageBitmap(), contentDescription = "Wi-Fi QR code for ${network.ssid}. Scan with a phone camera to join.",
            modifier = Modifier.fillMaxSize())
      }
    }
    Text("Point your phone camera here to join", color = ApprovedHomePalette.Secondary, fontSize = 22.sp,
        lineHeight = 30.sp, modifier = Modifier.width(400.dp))
  }
}

@Composable
private fun GuestWifiDetails(content: GuestCardContent) {
  val settings = content.settings
  var revealed by remember { mutableStateOf(!settings.hidePassword) }
  if (settings.welcome.isNotBlank()) {
    Text(settings.welcome, color = ApprovedHomePalette.Text, fontSize = 40.sp, lineHeight = 50.sp,
        fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
  }
  GuestLine("Wi-Fi network", settings.ssid, large = true)
  if (settings.wifiSecurity == GuestWifi.Security.NONE) {
    GuestLine("Password", "No password needed")
  } else if (revealed) {
    GuestLine("Password", content.password, large = true, monospace = true)
  } else {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      GuestLabel("Password")
      ToolsButton("Show password") { revealed = true }
    }
  }
}

@Composable
private fun GuestBasics(settings: GuestConfig.Settings) {
  val basics = settings.basics()
  if (basics.isEmpty()) return
  Spacer(Modifier.size(4.dp))
  basics.forEach { (label, value) -> GuestLine(label, value) }
}

@Composable
private fun GuestModeControls(mode: GuestModeState, onStart: (GuestSchedule) -> Unit, onEnd: () -> Unit) {
  Spacer(Modifier.size(8.dp))
  if (mode.isOn(System.currentTimeMillis())) {
    GuestLine("Guest mode is on", "Photos, calendar, notes and music library are hidden. ${guestUntilLabel(mode)}.")
    GuestHoldToEnd(onEnd)
  } else {
    GuestLabel("Guest mode hides photos, calendar, notes and your music library")
    GuestStartButtons(onStart)
  }
}

@Composable
private fun GuestStartButtons(onStart: (GuestSchedule) -> Unit) {
  GuestStartChoices.choices.chunked(2).forEach { row ->
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      row.forEach { (label, schedule) ->
        ToolsButton(label, primary = schedule == GuestSchedule.UntilEnded, modifier = Modifier.weight(1f)
            .semantics { contentDescription = "Start Guest mode, ${label.lowercase()}" }) { onStart(schedule) }
      }
      if (row.size == 1) Spacer(Modifier.weight(1f))
    }
  }
}

@Composable
private fun GuestLabel(text: String) {
  Text(text, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
}

@Composable
private fun GuestLine(label: String, value: String, large: Boolean = false, monospace: Boolean = false) {
  Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    GuestLabel(label)
    Text(value, color = ApprovedHomePalette.Text, fontSize = if (large) 40.sp else 28.sp,
        lineHeight = if (large) 50.sp else 38.sp, fontWeight = if (large) FontWeight.Medium else FontWeight.Normal,
        fontFamily = if (monospace) FontFamily.Monospace else null)
  }
}
