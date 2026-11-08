/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Step 1: pick the guest network from a scan; typing a name is only the "isn't listed" path. */
@Composable
internal fun GuestNetworkStep(draft: GuestSetupDraft, onDraft: (GuestSetupDraft) -> Unit) {
  var manual by remember { mutableStateOf(draft.settings.hiddenNetwork) }
  if (manual) {
    GuestManualNetwork(draft, onDraft) { manual = false }
  } else {
    GuestNetworkList(draft.settings.ssid, onPick = { onDraft(draft.pick(it)) }, onManual = { manual = true })
  }
}

private enum class Access { CHECKING, ASK, DENIED_FOREVER, LOCATION_OFF, WIFI_OFF, READY }

@Composable
private fun GuestNetworkList(selected: String, onPick: (GuestNetworks.Nearby) -> Unit, onManual: () -> Unit) {
  val context = LocalContext.current
  val scanner = remember(context) { GuestWifiScanner(context) }
  var access by remember { mutableStateOf(Access.CHECKING) }
  var networks by remember { mutableStateOf(emptyList<GuestNetworks.Nearby>()) }
  var revision by remember { mutableStateOf(0) }
  var asked by remember { mutableStateOf(false) }
  var deniedForever by remember { mutableStateOf(false) }
  fun recheck() {
    access = when (scanner.status()) {
      GuestWifiScanner.Status.NEEDS_PERMISSION -> if (deniedForever) Access.DENIED_FOREVER else Access.ASK
      GuestWifiScanner.Status.LOCATION_OFF -> Access.LOCATION_OFF
      GuestWifiScanner.Status.WIFI_OFF -> Access.WIFI_OFF
      GuestWifiScanner.Status.READY -> Access.READY
    }
    revision++
  }
  val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    deniedForever = !granted && GuestWifiScanner.permanentlyDenied(context)
    recheck()
  }
  GuestResumeEffect { recheck() }
  LaunchedEffect(access) {
    // Ask once, as the picker opens; after that only the Allow button asks.
    if (access == Access.ASK && !asked) { asked = true; request.launch(GuestWifiScanner.PERMISSION) }
    if (access == Access.READY) scanner.scan()
  }
  DisposableEffect(access) {
    val stop = if (access == Access.READY) scanner.listen { revision++ } else ({})
    onDispose { stop() }
  }
  LaunchedEffect(access, revision) {
    if (access == Access.READY) networks = withContext(Dispatchers.IO) { scanner.networks() }
  }
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    GuestAccessMessage(access, onAllow = { request.launch(GuestWifiScanner.PERMISSION) })
    if (access == Access.READY) GuestNetworkRows(networks, selected, onPick, onRefresh = { scanner.scan(); revision++ })
    ToolsButton("My network isn't listed", modifier = Modifier.fillMaxWidth(), onClick = onManual)
  }
}

@Composable
private fun GuestAccessMessage(access: Access, onAllow: () -> Unit) {
  val context = LocalContext.current
  when (access) {
    Access.CHECKING, Access.READY -> Unit
    Access.ASK -> GuestSetupGroup("Allow location to list networks", "Calder needs location permission to list nearby Wi-Fi networks.") {
      ToolsButton("Allow", primary = true, onClick = onAllow)
    }
    Access.DENIED_FOREVER -> GuestSetupGroup("Location permission is off",
        "Calder needs location permission to list nearby Wi-Fi networks. Turn it on in Android settings, or add your network by name.") {
      ToolsButton("Open Android settings") { GuestWifiScanner.openAppSettings(context) }
    }
    Access.LOCATION_OFF -> GuestSetupGroup("Location is off",
        "Android lists nearby Wi-Fi networks only while location is on.") {
      ToolsButton("Turn on location") { GuestWifiScanner.openLocationSettings(context) }
    }
    Access.WIFI_OFF -> GuestSetupGroup("Wi-Fi is off", "Turn on Wi-Fi on this Portal to list nearby networks.") {}
  }
}

@Composable
private fun GuestNetworkRows(
    networks: List<GuestNetworks.Nearby>, selected: String,
    onPick: (GuestNetworks.Nearby) -> Unit, onRefresh: () -> Unit,
) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    GuestHint(if (networks.isEmpty()) "Looking for networks…" else "Choose the network guests should join.",
        modifier = Modifier.weight(1f))
    ToolsButton("Refresh", onClick = onRefresh)
  }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    networks.forEach { GuestNetworkRow(it, it.ssid == selected) { onPick(it) } }
  }
}

@Composable
private fun GuestNetworkRow(network: GuestNetworks.Nearby, selected: Boolean, onClick: () -> Unit) {
  val shape = RoundedCornerShape(20.dp)
  val detail = listOfNotNull("Connected now".takeIf { network.connected }, network.security.label).joinToString(" · ")
  Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).clip(shape)
      .background(if (selected) ApprovedHomePalette.Selected else ApprovedHomePalette.Card).approvedOutline(shape)
      .tvFocusable(shape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent, onClick = onClick)
      .semantics(mergeDescendants = true) {
        role = Role.RadioButton; this.selected = selected
        contentDescription = "${network.ssid}, $detail, ${GuestNetworks.bars(network.level)} of 4 bars"
      }.padding(horizontal = 24.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    GuestSignalBars(GuestNetworks.bars(network.level))
    Column(Modifier.weight(1f)) {
      Text(network.ssid, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)
      Text(detail, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
    }
    if (selected) Text("Selected", color = ApprovedHomePalette.Accent, fontSize = 22.sp, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun GuestSignalBars(bars: Int) {
  val on = ApprovedHomePalette.Text
  val off = ApprovedHomePalette.Border
  Canvas(Modifier.size(36.dp)) {
    val gap = size.width / 12
    val w = (size.width - gap * 3) / 4
    for (i in 0 until 4) {
      val h = size.height * (i + 1) / 4
      drawRoundRect(if (i < bars) on else off, Offset(i * (w + gap), size.height - h), Size(w, h),
          androidx.compose.ui.geometry.CornerRadius(w / 3))
    }
  }
}

/** "My network isn't listed": name, security and hidden — the only typed network path. */
@Composable
private fun GuestManualNetwork(draft: GuestSetupDraft, onDraft: (GuestSetupDraft) -> Unit, onList: () -> Unit) {
  val s = draft.settings
  Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
    ApprovedEditorsField("Network name", s.ssid, { onDraft(draft.manual(it, s.wifiSecurity, s.hiddenNetwork)) },
        placeholder = "Exactly as it appears when joining")
    GuestSetupGroup("Security") {
      GuestChipRow {
        GuestWifi.Security.entries.forEach { sec ->
          GuestChoiceChip(sec.label, s.wifiSecurity == sec) { onDraft(draft.manual(s.ssid, sec, s.hiddenNetwork)) }
        }
      }
    }
    GuestToggleRow("Network is hidden", s.hiddenNetwork) { onDraft(draft.manual(s.ssid, s.wifiSecurity, it)) }
    ToolsButton("Back to nearby networks", onClick = onList)
  }
}

/** Runs [onResume] each time the screen resumes (returning from Android settings). */
@Composable
private fun GuestResumeEffect(onResume: () -> Unit) {
  val owner = LocalLifecycleOwner.current
  DisposableEffect(owner) {
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) onResume() }
    owner.lifecycle.addObserver(observer)
    if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onResume()
    onDispose { owner.lifecycle.removeObserver(observer) }
  }
}
