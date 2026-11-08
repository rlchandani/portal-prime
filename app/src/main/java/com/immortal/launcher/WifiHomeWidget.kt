/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.immortal.launcher.settings.GuestSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * QR modules always sit on white, in day and night themes alike: phone cameras need dark-on-light
 * contrast, so this is deliberately not a palette token.
 */
private val QrTileWhite = Color.White
private val QrTileShape = RoundedCornerShape(16.dp)
private const val SCAN_HINT = "Scan with your phone camera to join"

/**
 * Home's Wi-Fi widget: the Guest card's join QR, the network name and, at larger sizes, the
 * security and a "Show password" row. Reads the same store as the Guest card and reloads live.
 */
@Composable
internal fun WifiHomeWidget(modifier: Modifier = Modifier, layout: WifiWidgetLayout) {
  val context = LocalContext.current.applicationContext
  var enlarged by remember { mutableStateOf(false) }
  val openSetup = { GuestEntry.openSettings(context) }
  when (val state = rememberWifiWidgetState(context)) {
    null -> Box(modifier)
    WifiWidgetState.NotSetUp -> WifiEmpty(modifier, "Let visitors join by scanning a code", "Add your Wi-Fi", openSetup)
    WifiWidgetState.NeedsPassword -> WifiEmpty(modifier, "The Wi-Fi password is missing", "Finish Wi-Fi setup", openSetup)
    is WifiWidgetState.Ready -> {
      WifiReady(modifier, state, layout) { enlarged = true }
      if (enlarged) WifiQrFullScreen(state) { enlarged = false }
    }
  }
}

/** The widget's state, reloaded off the main thread whenever the Guest card changes. */
@Composable
private fun rememberWifiWidgetState(context: Context): WifiWidgetState? {
  var settingsTick by remember { mutableIntStateOf(0) }
  DisposableEffect(context) {
    val listener = GuestConfig.observe(context) { settingsTick++ }
    onDispose { GuestConfig.unobserve(context, listener) }
  }
  val secrets by GuestCardChanges.revision.collectAsState()
  val state by produceState<WifiWidgetState?>(null, settingsTick, secrets) {
    value = withContext(Dispatchers.IO) {
      WifiWidgetState.from(GuestSettings.domain.load(context), GuestPasswordStore.get(context).read())
    }
  }
  return state
}

@Composable
private fun WifiEmpty(modifier: Modifier, message: String, action: String, onClick: () -> Unit) {
  Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    ApprovedHomeWidgetHeading("Wi-Fi", "wifi", action = null) { }
    Text(message, color = ApprovedHomePalette.Text, fontSize = 24.sp, lineHeight = 32.sp,
        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    ToolsButton(action, primary = true, modifier = Modifier.fillMaxWidth(), onClick = onClick)
  }
}

@Composable
private fun WifiReady(modifier: Modifier, state: WifiWidgetState.Ready, layout: WifiWidgetLayout, onEnlarge: () -> Unit) {
  BoxWithConstraints(modifier.fillMaxSize().padding(20.dp)) {
    when {
      !layout.showsDetails -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WifiQrTile(state, Modifier.weight(1f).aspectRatio(1f, matchHeightConstraintsFirst = true), onEnlarge)
        WifiNetworkName(state.ssid, large = false)
      }
      maxWidth >= maxHeight -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp),
          verticalAlignment = Alignment.CenterVertically) {
        WifiQrTile(state, Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true), onEnlarge)
        WifiDetails(state, Modifier.weight(1f))
      }
      else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        WifiQrTile(state, Modifier.fillMaxWidth().aspectRatio(1f), onEnlarge)
        WifiDetails(state, Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun WifiDetails(state: WifiWidgetState.Ready, modifier: Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    WifiNetworkName(state.ssid, large = true)
    Text(state.securityLabel, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp, maxLines = 1)
    Text(SCAN_HINT, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (state.hasPasswordRow) WifiPasswordRow(state)
  }
}

@Composable
private fun WifiNetworkName(ssid: String, large: Boolean) {
  Text(ssid, color = ApprovedHomePalette.Text, fontSize = if (large) 30.sp else 24.sp,
      lineHeight = if (large) 38.sp else 30.sp, fontWeight = FontWeight.Medium, maxLines = if (large) 2 else 1,
      overflow = TextOverflow.Ellipsis)
}

/** "Show password" → visible for [WifiPasswordReveal.REVEAL_MS], then hidden again. Never spoken. */
@Composable
private fun WifiPasswordRow(state: WifiWidgetState.Ready) {
  var reveal by remember(state.password, state.hidePassword) { mutableStateOf(WifiPasswordReveal()) }
  LaunchedEffect(reveal) {
    val left = reveal.remainingMs(System.currentTimeMillis())
    if (left > 0) { delay(left); reveal = reveal.hide() }
  }
  if (reveal.visible(System.currentTimeMillis(), state.hidePassword)) {
    Text(state.password, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 34.sp,
        fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clearAndSetSemantics { contentDescription = "Wi-Fi password, shown on screen" })
  } else {
    ToolsButton("Show password") { reveal = reveal.reveal(System.currentTimeMillis()) }
  }
}

/** The join QR on its white quiet-zone tile; tapping enlarges it. */
@Composable
private fun WifiQrTile(state: WifiWidgetState.Ready, modifier: Modifier, onClick: (() -> Unit)?) {
  val qr by produceState<Bitmap?>(null, state.payload) {
    value = withContext(Dispatchers.Default) {
      lanSetupQr(state.payload, WifiQrOverlay.QR_PX, WifiQrOverlay.QUIET_ZONE_MODULES)
    }
  }
  val tap = if (onClick == null) Modifier else Modifier
      .tvFocusable(QrTileShape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent, onClick = onClick)
      .semantics { role = Role.Button }
  Box(modifier.clip(QrTileShape).background(QrTileWhite).approvedOutline(QrTileShape).then(tap)
      .semantics(mergeDescendants = true) { contentDescription = state.qrDescription }, contentAlignment = Alignment.Center) {
    qr?.let {
      Image(it.asImageBitmap(), contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
    }
  }
}

/** Full-screen enlarged QR: tap or Back closes it, the screen stays awake, and it closes after 2 min. */
@Composable
private fun WifiQrFullScreen(state: WifiWidgetState.Ready, onClose: () -> Unit) {
  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    KeepInteractiveScreenAwake()
    LaunchedEffect(Unit) { delay(WifiQrOverlay.AUTO_CLOSE_MS); onClose() }
    Column(Modifier.fillMaxSize().background(ApprovedHomePalette.Background)
        .tvFocusable(RoundedCornerShape(0.dp), focusScale = 1f, ringColor = ApprovedHomePalette.Background, onClick = onClose)
        .semantics { contentDescription = "${state.qrDescription} Tap to close." }.padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
      WifiQrTile(state, Modifier.weight(1f).aspectRatio(1f, matchHeightConstraintsFirst = true), onClick = null)
      Text(state.ssid, color = ApprovedHomePalette.Text, fontSize = 40.sp, lineHeight = 50.sp,
          fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("$SCAN_HINT · Tap anywhere to close", color = ApprovedHomePalette.Secondary, fontSize = 24.sp, lineHeight = 32.sp)
    }
  }
}
