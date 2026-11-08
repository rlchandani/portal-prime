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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val QR_PX = 720

/** What the card shows; [toString] never includes a secret. */
internal class GuestCardContent(
    val settings: GuestConfig.Settings,
    val password: String,
    val doorCode: String,
    val use24: Boolean,
) {
  val network: GuestWifi.Network
    get() = GuestWifi.Network(settings.ssid, password, settings.wifiSecurity, settings.hiddenNetwork)
  val ready: Boolean get() = GuestWifi.payload(network) != null
  override fun toString(): String = "GuestCardContent(ready=$ready)"
}

/** The Wi-Fi join QR code; blank until the network and password are complete. */
@Composable
internal fun GuestQr(network: GuestWifi.Network, size: Dp = 400.dp) {
  val payload = remember(network.ssid, network.password, network.security, network.hidden) { GuestWifi.payload(network) }
  val qr by produceState<Bitmap?>(null, payload) {
    value = payload?.let { withContext(Dispatchers.Default) { lanSetupQr(it, QR_PX) } }
  }
  val shape = RoundedCornerShape(28.dp)
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(Modifier.size(size).clip(shape).background(Color.White).approvedOutline(shape).padding(size / 20),
        contentAlignment = Alignment.Center) {
      val bitmap = qr
      if (bitmap != null && payload != null) {
        Image(bitmap.asImageBitmap(), contentDescription = "Wi-Fi QR code for ${network.ssid}. Scan with a phone camera to join.",
            modifier = Modifier.fillMaxSize())
      } else {
        Text("The QR code appears once the network is complete", color = ApprovedHomePalette.Secondary,
            fontSize = 22.sp, lineHeight = 30.sp)
      }
    }
    Text("Point your phone camera here to join", color = ApprovedHomePalette.Secondary, fontSize = 22.sp,
        lineHeight = 30.sp, modifier = Modifier.width(size))
  }
}

/** Welcome, network, password, door code and the house basics; [compact] for the setup preview. */
@Composable
internal fun GuestCardDetails(content: GuestCardContent, compact: Boolean = false) {
  val settings = content.settings
  if (settings.welcome.isNotBlank()) {
    Text(settings.welcome, color = ApprovedHomePalette.Text, fontSize = if (compact) 30.sp else 40.sp,
        lineHeight = if (compact) 38.sp else 50.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
  }
  GuestLine("Wi-Fi network", settings.ssid.ifBlank { "Not chosen yet" }, large = !compact)
  if (!settings.wifiSecurity.needsPassword) {
    GuestLine("Password", "No password needed")
  } else {
    GuestSecretLine("Password", content.password, hidden = settings.hidePassword, revealLabel = "Show password", large = !compact)
  }
  if (content.doorCode.isNotEmpty()) GuestSecretLine("Door code", content.doorCode, hidden = true, revealLabel = "Show door code")
  if (settings.checkout.isNotEmpty()) GuestLine("Check-out", "By " + GuestBasics.checkoutLabel(settings.checkout, content.use24))
  GuestGoodToKnow(settings)
  settings.contactLine?.let { GuestLine("Questions", it) }
}

/** A secret line behind a reveal button; re-hides when the hide choice changes. */
@Composable
private fun GuestSecretLine(label: String, value: String, hidden: Boolean, revealLabel: String, large: Boolean = false) {
  var revealed by remember(hidden, value) { mutableStateOf(!hidden) }
  if (revealed) {
    GuestLine(label, value.ifEmpty { "Not added yet" }, large = large, monospace = value.isNotEmpty())
  } else {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      GuestLabel(label)
      ToolsButton(revealLabel) { revealed = true }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuestGoodToKnow(settings: GuestConfig.Settings) {
  val facts = settings.presets.map { it.icon to it.label } + settings.notes.map { "notes" to it }
  if (facts.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    GuestLabel("Good to know")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      facts.forEach { (icon, text) -> GuestFact(icon, text) }
    }
  }
}

@Composable
private fun GuestFact(icon: String, text: String) {
  val shape = RoundedCornerShape(24.dp)
  Row(Modifier.heightIn(min = 56.dp).clip(shape).background(ApprovedHomePalette.Card).approvedOutline(shape)
      .semantics(mergeDescendants = true) {}.padding(horizontal = 18.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    ApprovedIcon(icon, Modifier.size(26.dp), tint = ApprovedHomePalette.Secondary)
    Text(text, color = ApprovedHomePalette.Text, fontSize = 22.sp, lineHeight = 30.sp)
  }
}

@Composable
internal fun GuestLabel(text: String) {
  Text(text, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
}

@Composable
internal fun GuestLine(label: String, value: String, large: Boolean = false, monospace: Boolean = false) {
  Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    GuestLabel(label)
    Text(value, color = ApprovedHomePalette.Text, fontSize = if (large) 40.sp else 28.sp,
        lineHeight = if (large) 50.sp else 38.sp, fontWeight = if (large) FontWeight.Medium else FontWeight.Normal,
        fontFamily = if (monospace) FontFamily.Monospace else null)
  }
}
