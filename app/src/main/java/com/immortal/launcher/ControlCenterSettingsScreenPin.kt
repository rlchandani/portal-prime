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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sets the kids PIN: digits typed twice on a numeric pad ([CcPinSetter]); [onDone] gets the
 * agreed PIN. Its own small pad (the alarm keypad belongs to Security).
 */
@Composable
internal fun CcPinSetOverlay(onCancel: () -> Unit, onDone: (String) -> Unit) {
  val c = LocalCc.current
  var pin by remember { mutableStateOf(CcPinSet()) }
  LaunchedEffect(pin.done) { pin.done?.let(onDone) }
  Box(Modifier.fillMaxSize().background(c.scrim).dismissScrim("Cancel", onCancel), contentAlignment = Alignment.Center) {
    Column(Modifier.width(640.dp).ccCard(CcStyle.Modal).blockScrimTaps().padding(44.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
      Text(pin.title, color = c.text, fontSize = 44.sp, fontFamily = CcStyle.Serif)
      Text(pin.subtitle, color = if (pin.error != null) c.red else c.sec, fontSize = 21.sp, textAlign = TextAlign.Center, lineHeight = 28.sp)
      PinDots(pin.entry.length)
      PinKeys(onDigit = { pin = CcPinSetter.digit(pin, it) }, onBack = { pin = CcPinSetter.back(pin) },
          onOk = { pin = CcPinSetter.submit(pin) }, okEnabled = pin.canSubmit)
      CcButton("Cancel", onCancel, height = 64)
    }
  }
}

@Composable
private fun PinDots(filled: Int) {
  val c = LocalCc.current
  val shown = maxOf(ControlCenterConfig.MIN_PIN, filled)
  Row(Modifier.padding(vertical = 8.dp).semantics { contentDescription = "$filled digits entered" }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    repeat(shown) { i ->
      Box(Modifier.size(22.dp).clip(CircleShape).then(if (i < filled) Modifier.background(c.text) else Modifier.border(2.dp, c.faint, CircleShape)))
    }
  }
}

@Composable
private fun PinKeys(onDigit: (Char) -> Unit, onBack: () -> Unit, onOk: () -> Unit, okEnabled: Boolean) {
  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    KEY_ROWS.forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { row.forEach { d -> PinKey(d.toString(), d.toString()) { onDigit(d) } } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
      PinKey("⌫", "Delete", onClick = onBack)
      PinKey("0", "0") { onDigit('0') }
      PinKey("OK", "Done", enabled = okEnabled, primary = true, onClick = onOk)
    }
  }
}

@Composable
private fun PinKey(label: String, description: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
  val c = LocalCc.current
  Box(Modifier.size(KEY_DP.dp).clip(CircleShape).background(if (primary && enabled) c.text else c.surface2)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description },
      contentAlignment = Alignment.Center) {
    Text(label, color = if (primary && enabled) c.bg else if (enabled) c.text else c.faint, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
  }
}

private val KEY_ROWS = listOf("123", "456", "789").map { it.toList() }
private const val KEY_DP = 96
