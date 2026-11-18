/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The mock's motion curves: `--ease` (sheets) and `--spring` (modals). */
internal val CcEase = CubicBezierEasing(0.22f, 0.8f, 0.26f, 1f)
internal val CcSpringEase = CubicBezierEasing(0.34f, 1.32f, 0.64f, 1f)
internal const val CC_SHEET_MS = 380
internal const val CC_SCRIM_MS = 250
internal const val CC_MODAL_MS = 300
internal const val CC_MODAL_FADE_MS = 200

/** Whether [e] can be acted on here, and if not, why (connection first, then kids mode). */
internal data class CcGate(val enabled: Boolean, val reason: String?)

internal fun ccGate(s: CcUiState, e: CcEntity): CcGate {
  s.connection.disabledReason?.let { return CcGate(false, it) }
  if (CcPolicy.readOnly(e, s.settings)) return CcGate(false, "Kids mode · look only")
  if (e.unavailable) return CcGate(false, "${e.name} isn't responding to Home Assistant")
  return CcGate(true, null)
}

/** `.chip` (and `.chip.on`). */
@Composable
internal fun CcCtlChip(label: String, onClick: (() -> Unit)?, icon: String? = null, on: Boolean = false, enabled: Boolean = true) {
  val c = LocalCc.current
  Row(Modifier.height(64.dp).alpha(if (enabled) 1f else CC_DISABLED_ALPHA).clip(CcStyle.Pill).background(if (on) c.sel else c.surface2)
      .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
      .padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    icon?.let { CcIcon(it, c.text, 26.dp) }
    Text(label, color = c.text, fontSize = 20.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
  }
}

/** `.tag` with a leading icon (room sheet meta row). */
@Composable
internal fun CcCtlTag(text: String, icon: String? = null, tone: CcTone = CcTone.NEUTRAL) {
  val c = LocalCc.current
  val (bg, fg) = when (tone) {
    CcTone.NEUTRAL -> c.surface2 to c.sec
    CcTone.GREEN -> c.greenBg to c.green
    CcTone.AMBER -> c.amberBg to c.amber
    CcTone.RED -> c.redBg to c.red
  }
  Row(Modifier.height(34.dp).clip(CcStyle.Pill).background(bg).padding(horizontal = 13.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    icon?.let { CcIcon(it, fg, 20.dp) }
    Text(text, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
  }
}

/** The uppercase `.secT` heading used inside sheets. */
@Composable
internal fun CcCtlSection(text: String, top: Int = 22) {
  CcSectionLabel(text, Modifier.padding(top = top.dp, bottom = 12.dp))
}

/** Why the controls are off, under the title (never queue: say why instead). */
@Composable
internal fun CcCtlReason(gate: CcGate) {
  val c = LocalCc.current
  val reason = gate.reason ?: return
  Row(Modifier.padding(bottom = 14.dp).clip(CcStyle.Pill).background(c.surface2).padding(horizontal = 18.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    CcIcon("wifioff", c.sec, 24.dp)
    Text(reason, color = c.sec, fontSize = 20.sp)
  }
}

/** Soft progress ring while a call for this device is in flight. */
@Composable
internal fun CcCtlBusy(busy: Boolean, size: Int = 30) {
  if (!busy) return
  val c = LocalCc.current
  CircularProgressIndicator(Modifier.size(size.dp).semantics { contentDescription = "Working" }, color = c.sec, strokeWidth = 3.dp,
      trackColor = Color.Transparent)
}

/**
 * `.hslider`: a 76 dp pill you drag or tap. [value] is 0…100; [onLive] fires while dragging (for
 * throttled sends) and [onCommit] once on release. Local drag state keeps it from jumping back.
 */
@Composable
internal fun CcCtlHSlider(value: Int, label: (Int) -> String, onCommit: (Int) -> Unit, modifier: Modifier = Modifier,
                          enabled: Boolean = true, onLive: ((Int) -> Unit)? = null, description: String = "Slider") {
  val c = LocalCc.current
  var drag by remember { mutableStateOf<Int?>(null) }
  val commit by rememberUpdatedState(onCommit)
  val live by rememberUpdatedState(onLive)
  val shown = drag ?: value
  Box(modifier.height(76.dp).fillMaxWidth().alpha(if (enabled) 1f else CC_DISABLED_ALPHA).clip(CcStyle.Pill).background(c.surface2)
      .semantics { contentDescription = "$description ${label(shown)}" }
      .then(if (!enabled) Modifier else Modifier
          .pointerInput(Unit) {
            detectTapGestures { o -> commit(CcControlMath.pctFromHorizontal(o.x, size.width.toFloat())) }
          }
          .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { o -> drag = CcControlMath.pctFromHorizontal(o.x, size.width.toFloat()) },
                onDragEnd = { drag?.let { commit(it) }; drag = null },
                onDragCancel = { drag = null },
            ) { change, _ ->
              val p = CcControlMath.pctFromHorizontal(change.position.x, size.width.toFloat())
              drag = p; live?.invoke(p); change.consume()
            }
          })) {
    Box(Modifier.fillMaxHeight().fillMaxWidth(shown / 100f).background(c.text.copy(alpha = 0.85f)))
    Text(label(shown), color = if (shown > LABEL_ON_FILL) c.bg else c.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp))
  }
}

/** One `.cklist` row. */
@Composable
internal fun CcCtlCheckRow(line: CcCheckLine, trailing: (@Composable () -> Unit)? = null) {
  val c = LocalCc.current
  val tint = if (line.warn) c.amber else c.text
  Row(Modifier.fillMaxWidth().height(if (trailing != null) 72.dp else 44.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    CcIcon(line.icon, tint, 32.dp)
    Text(line.text, color = tint, fontSize = 23.sp, modifier = Modifier.weight(1f))
    trailing?.invoke()
  }
}

@Composable
internal fun CcCtlNote(text: String, modifier: Modifier = Modifier) {
  Text(text, color = LocalCc.current.sec, fontSize = 19.sp, lineHeight = 26.sp, modifier = modifier)
}

/** A designed "nothing here" card for sheets. */
@Composable
internal fun CcCtlEmpty(icon: String, title: String, body: String, action: Pair<String, () -> Unit>? = null) {
  val c = LocalCc.current
  Column(Modifier.fillMaxWidth().ccCard(CcStyle.Tile).padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(Modifier.size(64.dp).clip(CcStyle.CardSmall).background(c.surface2), contentAlignment = Alignment.Center) { CcIcon(icon, c.sec, 32.dp) }
    Text(title, color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    Text(body, color = c.sec, fontSize = 21.sp, lineHeight = 29.sp)
    action?.let { (l, f) -> CcButton(l, f, height = 64) }
  }
}

private const val LABEL_ON_FILL = 30
