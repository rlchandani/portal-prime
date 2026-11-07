/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Typed message with the Portal's own keyboard; the counter stops at 40 code points. */
@Composable
internal fun DinnerComposePane(targets: List<String>, onCancel: () -> Unit, onSend: (DinnerMessage) -> Unit) {
  var text by remember { mutableStateOf("") }
  val focus = remember { FocusRequester() }
  val message = DinnerMessage.custom(text)
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(bottom = 32.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Type a message", color = ApprovedHomePalette.Text, fontSize = 44.sp, fontWeight = FontWeight.Medium)
      Text("${targets.joinToString(" and ")} shows it and says it once", color = ApprovedHomePalette.Secondary, fontSize = 24.sp,
          modifier = Modifier.weight(1f).padding(horizontal = 32.dp), maxLines = 1)
      ApprovedHomePillButton("Cancel", onClick = onCancel)
      Spacer(Modifier.width(16.dp))
      DinnerSolidButton("Send", enabled = message != null) { message?.let(onSend) }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { next -> text = DinnerInput.limit(next) },
        placeholder = { Text("For example: Pizza’s here", fontSize = 40.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 44.sp, color = ApprovedHomePalette.Text),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { message?.let(onSend) }),
        suffix = {
          val n = text.codePointCount(0, text.length)
          Text("$n / ${DinnerMessage.MAX_CUSTOM}", fontSize = 26.sp,
              color = if (n >= DinnerMessage.MAX_CUSTOM) DinnerColors.Glyph else ApprovedHomePalette.Secondary)
        },
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DinnerColors.Glyph, unfocusedBorderColor = ApprovedHomePalette.Border,
            cursorColor = DinnerColors.Glyph),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(focus),
    )
  }
}

/** Typing stops at the limit (counted in code points so emoji aren't split). */
internal object DinnerInput {
  fun limit(next: String): String {
    val single = next.replace('\n', ' ')
    return if (single.codePointCount(0, single.length) <= DinnerMessage.MAX_CUSTOM) single
    else single.substring(0, single.offsetByCodePoints(0, DinnerMessage.MAX_CUSTOM))
  }
}

@Composable
internal fun DinnerSolidButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
  val shape = RoundedCornerShape(40.dp)
  Box(Modifier.heightIn(min = 80.dp).clip(shape).background(ApprovedHomePalette.Text.copy(alpha = if (enabled) 1f else .3f))
      .tvFocusable(shape, enabled = enabled, onClick = onClick).padding(horizontal = 44.dp, vertical = 18.dp),
      contentAlignment = Alignment.Center) {
    Text(label, color = ApprovedHomePalette.Background, fontSize = 30.sp, fontWeight = FontWeight.Medium)
  }
}

private fun statusColor(status: DinnerStatus): Color = when {
  status == DinnerStatus.SENDING -> Color.Unspecified
  status.failed -> DinnerColors.Bad
  status == DinnerStatus.QUIET -> DinnerColors.Quiet
  else -> DinnerColors.Ok
}

/** One row per room, Try again where it can help, and replies under their room. */
@Composable
internal fun DinnerResultDialog(send: DinnerSend, onRetry: (String) -> Unit, onDone: () -> Unit) {
  Box(Modifier.fillMaxSize().background(DinnerColors.Scrim), contentAlignment = Alignment.Center) {
    val shape = RoundedCornerShape(36.dp)
    Column(Modifier.width(1080.dp).clip(shape).background(ApprovedHomePalette.Background).border(1.dp, ApprovedHomePalette.Border, shape)
        .padding(horizontal = 54.dp, vertical = 50.dp)) {
      Text(DinnerCopy.resultTitle(send), color = ApprovedHomePalette.Text, fontSize = 44.sp, fontWeight = FontWeight.Medium)
      HorizontalDivider(Modifier.padding(vertical = 28.dp), color = ApprovedHomePalette.Border)
      if (send.rows.isEmpty()) Text("No other Portal answered.", color = ApprovedHomePalette.Secondary, fontSize = 26.sp)
      send.rows.forEach { row -> ResultRow(row, onRetry) }
      HorizontalDivider(Modifier.padding(vertical = 28.dp), color = ApprovedHomePalette.Border)
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { DinnerSolidButton("Done", onClick = onDone) }
    }
  }
}

@Composable
private fun ResultRow(row: DinnerRow, onRetry: (String) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(painterResource(DinnerIcons.resource("display")), null, tint = ApprovedHomePalette.Secondary, modifier = Modifier.size(34.dp))
    Text(row.roomName, color = ApprovedHomePalette.Text, fontSize = 30.sp, modifier = Modifier.weight(1f).padding(start = 22.dp))
    val color = statusColor(row.status).takeIf { it != Color.Unspecified } ?: ApprovedHomePalette.Secondary
    Text(row.status.rowText, color = color, fontSize = 28.sp, textAlign = TextAlign.End)
    if (row.status.retryable) {
      Spacer(Modifier.width(20.dp))
      ApprovedHomePillButton("Try again", onClick = { onRetry(row.roomId) })
    }
  }
  row.reply?.let { reply ->
    Row(Modifier.padding(start = 56.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(painterResource(DinnerIcons.resource("reply")), null, tint = DinnerColors.Glyph, modifier = Modifier.size(30.dp))
      Text(buildAnnotatedString {
        append("${row.roomName} replied: ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(reply.label) }
      }, color = ApprovedHomePalette.Text, fontSize = 28.sp, modifier = Modifier.padding(start = 16.dp))
    }
  }
}

/** Ping confirms with a toast, not a dialog. */
@Composable
internal fun DinnerPingToast(send: DinnerSend, onRetry: (String) -> Unit) {
  Box(Modifier.fillMaxSize().padding(bottom = 140.dp), contentAlignment = Alignment.BottomCenter) {
    val shape = RoundedCornerShape(40.dp)
    val bad = send.rows.firstOrNull { it.status.retryable }
    Row(Modifier.clip(shape).background(ApprovedHomePalette.Chrome).padding(horizontal = 36.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically) {
      Icon(painterResource(DinnerIcons.resource("bell")), null, tint = if (bad != null && !send.pending) DinnerColors.Bad else ApprovedHomePalette.Text,
          modifier = Modifier.size(30.dp))
      Text(DinnerCopy.pingToast(send), color = ApprovedHomePalette.Text, fontSize = 28.sp, modifier = Modifier.padding(start = 18.dp))
      if (bad != null && !send.pending) {
        Spacer(Modifier.width(20.dp))
        ApprovedHomeTextButton("Try again") { onRetry(bad.roomId) }
      }
    }
  }
}
