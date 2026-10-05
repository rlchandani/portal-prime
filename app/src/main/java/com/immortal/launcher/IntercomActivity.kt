/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.ui.theme.PortalPrimeTheme

/**
 * Portal-to-Portal LAN intercom / baby monitor (one-way audio). One Portal
 * broadcasts its mic; another listens. No servers, no GMS — raw PCM over TCP on the
 * local network (see [LanAudio]). Needs the mic permission, requested on first use.
 */
class IntercomActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { IntercomScreen() } }
  }
}

@Composable
private fun IntercomScreen() {
  val activity = LocalContext.current as? Activity
  PopupScaffold(
      title = "Intercom",
      subtitle = "One-way audio between two Portals on the same Wi-Fi. No internet, no servers.",
      onBack = { activity?.finish() },
  ) {
    IntercomContent()
  }
}

/**
 * The intercom body, shared by the standalone [IntercomScreen] popup and the in-page Tools overlay.
 * Owns the [LanAudio] session and stops it when hidden, stopped or disposed.
 */
@Composable
internal fun IntercomContent() {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val pageActive by rememberUpdatedState(LocalApprovedLauncherPageActive.current)
  val lan = remember { LanAudio() }

  var mode by remember { mutableStateOf("idle") } // idle | broadcasting | listening
  var host by remember { mutableStateOf("") }
  var status by remember { mutableStateOf("") }
  var live by remember { mutableStateOf(true) }
  var session by remember { mutableStateOf(0) }
  var permissionSession by remember { mutableStateOf<Int?>(null) }
  val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
  val myIp = remember { localIp(context) }

  fun isActive() = live && pageActive && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
  fun stop() {
    session++
    permissionSession = null
    lan.stop()
    mode = "idle"
    status = ""
  }
  DisposableEffect(owner, lan) {
    live = true
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP) stop()
    }
    owner.lifecycle.addObserver(observer)
    if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) stop()
    onDispose {
      live = false
      owner.lifecycle.removeObserver(observer)
      stop()
    }
  }

  fun beginBroadcast() {
    if (!isActive()) return
    val request = ++session
    status = "Starting…"
    lan.startBroadcast { ok ->
      handler.post {
        if (isActive() && request == session) {
          if (ok) { mode = "broadcasting"; status = "Broadcasting this room's audio" }
          else { mode = "idle"; status = "Couldn't start — the broadcast port is in use" }
        }
      }
    }
  }

  val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
      androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
    val request = permissionSession
    permissionSession = null
    if (request != null && request == session && isActive()) {
      if (granted) beginBroadcast()
      else status = "Microphone permission is needed to broadcast"
    }
  }

  fun startBroadcast() {
    if (!isActive()) return
    val granted = android.content.pm.PackageManager.PERMISSION_GRANTED ==
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
    if (granted) beginBroadcast()
    else {
      permissionSession = ++session
      permLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }
  }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      if (myIp.isNotBlank()) {
        Card {
          Text("This Portal's address: $myIp", color = IosLabel, fontSize = Sizes.rowSub,
              modifier = Modifier.padding(16.dp))
        }
      }

      if (status.isNotBlank()) {
        Text(status, color = IosBlue, fontSize = Sizes.rowSub, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
      }

      // Broadcast (baby monitor in the nursery): this device's mic → the network.
      ActionButton(
          label = if (mode == "broadcasting") "■ Stop broadcasting" else "🔊 Broadcast this room",
          primary = mode != "broadcasting",
      ) {
        if (mode == "broadcasting") stop() else startBroadcast()
      }

      // Listen (kitchen Portal): connect to the broadcasting Portal's address.
      Card {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedTextField(colors = iosTextFieldColors(), value = host, onValueChange = { host = it }, singleLine = true,
              label = { Text("Other Portal's address (e.g. 192.168.1.42)") },
              modifier = Modifier.fillMaxWidth())
          ActionButton(
              label = if (mode == "listening") "■ Stop listening" else "👂 Listen",
              primary = mode != "listening",
          ) {
            if (mode == "listening") stop()
            else if (host.isNotBlank() && isActive()) {
              val request = ++session
              val address = host.trim()
              status = "Connecting…"
              lan.startListening(address) { ok ->
                handler.post {
                  if (isActive() && request == session) {
                    status = if (ok) "Listening to $address" else "Couldn't connect to $address"
                    mode = if (ok) "listening" else "idle"
                  }
                }
              }
            }
          }
        }
      }
    }
}

@Composable
private fun ActionButton(label: String, primary: Boolean, onClick: () -> Unit) {
  Surface(
      color = if (primary) IosBlue else IosRed,
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) { onClick() },
  ) {
    Text(label, color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError,
        fontSize = Sizes.actionButton, fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth())
  }
}

@Suppress("DEPRECATION")
private fun localIp(context: Context): String = runCatching {
  val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
  val ip = wm.connectionInfo.ipAddress
  if (ip == 0) "" else String.format("%d.%d.%d.%d",
      ip and 0xff, (ip shr 8) and 0xff, (ip shr 16) and 0xff, (ip shr 24) and 0xff)
}.getOrDefault("")
