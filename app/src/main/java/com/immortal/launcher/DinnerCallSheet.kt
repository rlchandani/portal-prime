/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Quick controls / Tools tile for the integrator (QC(icon, label, description) on Home). */
internal object DinnerCallTile {
  const val ICON = "megaphone"
  const val LABEL = "Call everyone"
  const val DESCRIPTION = "Chime and say a message in every room"
}

internal object DinnerColors {
  val Glyph = Color(0xFFF2D58A)
  val GlyphBackground = Color(0xFF4A4535)
  val Ok = Color(0xFF66C49E)
  val Quiet = Color(0xFFB8B8B8)
  val Bad = Color(0xFFFF747D)
  val Scrim = Color(0x99000000)
}

private sealed class DinnerStage {
  object Presets : DinnerStage()
  object Compose : DinnerStage()
  data class Result(val id: String) : DinnerStage()
  data class Ping(val id: String) : DinnerStage()
}

/**
 * The Call everyone sheet: six presets, Type a message (40 characters), Ping, then per-room
 * results with replies. Integrator: render over Home when the Quick controls / Tools tile is
 * tapped, e.g. `if (showDinner) DinnerCallSheet(onDismiss = { showDinner = false })`.
 */
@Composable
internal fun DinnerCallSheet(onDismiss: () -> Unit) {
  val context = LocalContext.current
  var stage by remember { mutableStateOf<DinnerStage>(DinnerStage.Presets) }
  val peers by DinnerCall.peers.collectAsState()
  val book by DinnerCall.book.collectAsState()
  val selfId = remember { DinnerCall.selfId(context) }
  val ready = remember { DinnerCall.householdReady(context) }
  val offline = remember { !isOnline(context) }
  LaunchedEffect(Unit) { DinnerCall.hello(context, force = true) }
  val others = peers.filter { it.id != selfId }
  fun send(message: DinnerMessage) {
    val sent = DinnerCall.sendFromPortal(context, message) ?: return
    stage = if (message.kind == DinnerKind.PING) DinnerStage.Ping(sent.id) else DinnerStage.Result(sent.id)
  }
  Box(Modifier.fillMaxSize()) {
    when (val s = stage) {
      DinnerStage.Presets -> Sheet(onScrim = onDismiss) {
        if (!ready || others.isEmpty()) DinnerEmptyState(onDismiss)
        else DinnerPresetPane(
            toLine = DinnerCopy.toLine(others.map { it.name }, others.filter { it.quiet == true }.map { it.name }, offline),
            enabled = !offline, offline = offline, onClose = onDismiss,
            onPreset = { send(it) }, onPreview = { DinnerCall.preview(context, it) },
            onCompose = { stage = DinnerStage.Compose }, onPing = { send(DinnerMessage.ping()) })
      }
      DinnerStage.Compose -> Sheet(onScrim = null) {
        DinnerComposePane(targets = others.map { it.name }, onCancel = { stage = DinnerStage.Presets }, onSend = { send(it) })
      }
      is DinnerStage.Result -> {
        DisposableEffect(s.id) { DinnerCall.visibleSendId = s.id; onDispose { DinnerCall.visibleSendId = null } }
        book.find(s.id)?.let { DinnerResultDialog(it, onRetry = { room -> DinnerCall.retry(context, s.id, room) }, onDone = onDismiss) }
      }
      is DinnerStage.Ping -> {
        val send = book.find(s.id)
        LaunchedEffect(send?.pending) { if (send?.pending == false) { delay(PING_TOAST_MS); onDismiss() } }
        send?.let { DinnerPingToast(it, onRetry = { room -> DinnerCall.retry(context, s.id, room) }) }
      }
    }
  }
}

private const val PING_TOAST_MS = 3_000L

private fun isOnline(context: Context): Boolean = runCatching {
  val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching true
  cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true ||
      cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
}.getOrDefault(true)

@Composable
private fun Sheet(onScrim: (() -> Unit)?, content: @Composable BoxScope.() -> Unit) {
  Box(Modifier.fillMaxSize().background(DinnerColors.Scrim).then(
      if (onScrim != null) Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onScrim) else Modifier))
  Box(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 108.dp, bottom = 24.dp)) {
    val shape = RoundedCornerShape(36.dp)
    Box(Modifier.fillMaxSize().clip(shape).background(ApprovedHomePalette.Background).border(1.dp, ApprovedHomePalette.Border, shape)
        .clickable(remember { MutableInteractionSource() }, null) {}.padding(40.dp), content = content)
  }
}

@Composable
internal fun DinnerGlyph(name: String, size: Int, tint: Color = DinnerColors.Glyph, background: Color = DinnerColors.GlyphBackground) {
  Box(Modifier.size(size.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
    Icon(painterResource(DinnerIcons.resource(name)), contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.46f).dp))
  }
}

@Composable
private fun SheetHeader(title: String, line: String, lineIcon: String, warn: Boolean, trailing: @Composable () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(bottom = 32.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(title, color = ApprovedHomePalette.Text, fontSize = 44.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    Icon(painterResource(DinnerIcons.resource(lineIcon)), null, tint = if (warn) DinnerColors.Bad else ApprovedHomePalette.Secondary,
        modifier = Modifier.size(28.dp))
    Text(line, color = if (warn) DinnerColors.Bad else ApprovedHomePalette.Secondary, fontSize = 24.sp,
        modifier = Modifier.padding(start = 14.dp, end = 32.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    trailing()
  }
}

@Composable
private fun RoundButton(icon: String, label: String, onClick: () -> Unit) {
  Box(Modifier.size(76.dp).clip(CircleShape).background(ApprovedHomePalette.Chrome)
      .tvFocusable(CircleShape, onClick = onClick), contentAlignment = Alignment.Center) {
    Icon(painterResource(DinnerIcons.resource(icon)), contentDescription = label, tint = ApprovedHomePalette.Text, modifier = Modifier.size(30.dp))
  }
}

@Composable
private fun DinnerPresetPane(
    toLine: String, enabled: Boolean, offline: Boolean, onClose: () -> Unit,
    onPreset: (DinnerMessage) -> Unit, onPreview: (DinnerMessage) -> Unit, onCompose: () -> Unit, onPing: () -> Unit,
) {
  Column(Modifier.fillMaxSize()) {
    SheetHeader("Call everyone", toLine, if (offline) "wifi" else "display", warn = offline) { RoundButton("x", "Close", onClose) }
    DinnerPresets.all.chunked(3).forEach { row ->
      Row(Modifier.fillMaxWidth().weight(1f).padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        row.forEach { preset ->
          val message = DinnerMessage.preset(preset.id)!!
          val shape = RoundedCornerShape(30.dp)
          Column(Modifier.weight(1f).fillMaxHeight().clip(shape).background(ApprovedHomePalette.Card)
              .border(1.dp, ApprovedHomePalette.Border, shape)
              .tvFocusable(shape, enabled = enabled, onLongClick = { onPreview(message) }) { onPreset(message) }
              .padding(40.dp), verticalArrangement = Arrangement.Center) {
            DinnerGlyph(preset.icon, 132)
            Spacer(Modifier.height(30.dp))
            Text(preset.text, color = ApprovedHomePalette.Text.copy(alpha = if (enabled) 1f else .4f), fontSize = 44.sp,
                lineHeight = 52.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
        }
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
      CallRow("keyboard", "Type a message", "Up to ${DinnerMessage.MAX_CUSTOM} characters", enabled, Modifier.weight(1f), onCompose)
      CallRow("bell", "Ping", "Just the chime, no words", enabled, Modifier.weight(1f), onPing)
    }
    Text("Touch and hold a message to hear it here first.", color = ApprovedHomePalette.Secondary, fontSize = 22.sp,
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 22.dp))
  }
}

@Composable
private fun CallRow(icon: String, title: String, detail: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
  val shape = RoundedCornerShape(26.dp)
  Row(modifier.clip(shape).border(2.dp, ApprovedHomePalette.Border, shape).tvFocusable(shape, enabled = enabled, onClick = onClick)
      .padding(horizontal = 30.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
    DinnerGlyph(icon, 76, tint = ApprovedHomePalette.Text, background = ApprovedHomePalette.Chrome)
    Column(Modifier.padding(start = 24.dp)) {
      Text(title, color = ApprovedHomePalette.Text, fontSize = 30.sp, fontWeight = FontWeight.Medium)
      Text(detail, color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
    }
  }
}

@Composable
private fun DinnerEmptyState(onClose: () -> Unit) {
  Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    DinnerGlyph("display", 132)
    Text("No other Portals to call yet", color = ApprovedHomePalette.Text, fontSize = 40.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 36.dp))
    Text("Call everyone reaches the other Portals on your home Wi-Fi. Pair one from Toolkit on your phone, then it shows up here.",
        color = ApprovedHomePalette.Secondary, fontSize = 24.sp, lineHeight = 34.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 40.dp).fillMaxWidth(0.6f))
    ApprovedHomePillButton("Close", onClick = onClose)
  }
}
