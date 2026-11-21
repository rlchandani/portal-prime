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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/**
 * Mock `joinScr`: type the code read out from the other home, on big keys (100 dp tall; 88 on a
 * Mini, where the keypad sits under the code). The
 * typed code lives in [entry], owned by the caller; [onJoin] fires only with a complete code.
 * Joining is the joiner's first step ([HomeLinkJoiner.start]).
 */
@Composable
internal fun HomeLinkJoinScreen(entry: HomeLinkCodeEntry, onEntry: (HomeLinkCodeEntry) -> Unit, onJoin: (HomeLinkCode) -> Unit,
                                onBack: () -> Unit) {
  val p = LocalApprovedPalette.current
  var letters by rememberSaveable { mutableStateOf(false) }
  val compact = currentLayoutClass().compact
  val code = entry.code
  val onKey: (String) -> Unit = { key ->
    when (key) {
      HomeLinkKeypad.LETTERS -> letters = true
      HomeLinkKeypad.DIGITS -> letters = false
      HomeLinkKeypad.DELETE -> onEntry(entry.backspace())
      else -> onEntry(entry.type(key.single()))
    }
  }
  val join: @Composable () -> Unit = {
    CallPill(HomeLinkCopy.JOIN, { code?.let(onJoin) }, icon = "check", style = CallButtonStyle.OK, size = CallButtonSize.LG,
        enabled = code != null, surface = callsSurface())
  }
  Column(Modifier.fillMaxSize().background(p.background)) {
    HomeLinkHeader(HomeLinkCopy.JOIN_TITLE, onBack = onBack)
    if (compact) {
      // Mini: code on top, keypad full width under it, Join last (thumb reach).
      Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(HomeLinkCopy.JOIN_H2, color = p.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        HomeLinkSlots(entry.slots, entry.cursor, spoken = typedSpoken(entry))
        Text(HomeLinkCopy.JOIN_HOW, color = p.secondary, fontSize = 20.sp, textAlign = TextAlign.Center)
        HomeLinkKeys(letters, compact = true, modifier = Modifier.fillMaxWidth(), onKey = onKey)
        join()
      }
    } else {
      Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(60.dp)) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(30.dp)) {
          Text(HomeLinkCopy.JOIN_H2, color = p.text, fontSize = 36.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
          HomeLinkSlots(entry.slots, entry.cursor, spoken = typedSpoken(entry))
          Text(HomeLinkCopy.JOIN_HOW, color = p.secondary, fontSize = 24.sp, textAlign = TextAlign.Center)
          join()
        }
        // One fixed slot for both pads (the wider one), so toggling ABC/123 doesn't slide the code across.
        Box(Modifier.width(LETTER_PAD), contentAlignment = Alignment.CenterEnd) {
          HomeLinkKeys(letters, compact = false, modifier = Modifier.width(if (letters) LETTER_PAD else DIGIT_PAD), onKey = onKey)
        }
      }
    }
  }
}

/** `.keys`: a grid of big keys, 3 across for digits, 6 for letters. */
@Composable
private fun HomeLinkKeys(letters: Boolean, compact: Boolean, modifier: Modifier, onKey: (String) -> Unit) {
  val p = LocalApprovedPalette.current
  val shape = RoundedCornerShape(28.dp)
  val columns = HomeLinkKeypad.columns(letters)
  Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
    HomeLinkKeypad.keys(letters).chunked(columns).forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        row.forEach { key ->
          Box(Modifier.weight(1f).height(HomeLinkLayout.keyHeight(compact, letters)).background(p.surface, shape).border(1.5.dp, p.outline, shape)
              .callClick(keySpoken(key), true) { onKey(key) }, contentAlignment = Alignment.Center) {
            Text(key, color = p.text, fontSize = if (key.length == 1) 40.sp else 30.sp, fontWeight = FontWeight.SemiBold)
          }
        }
      }
    }
  }
}

private fun keySpoken(key: String): String = when (key) {
  HomeLinkKeypad.DELETE -> "Delete"
  HomeLinkKeypad.LETTERS -> "Letters"
  HomeLinkKeypad.DIGITS -> "Numbers"
  else -> key
}

private fun typedSpoken(entry: HomeLinkCodeEntry): String =
    if (entry.symbols.isEmpty()) "Code, nothing typed yet"
    else "Code typed: ${entry.symbols.toList().joinToString(" ")}, ${entry.symbols.length} of ${HomeLinkCode.LENGTH}"

private val DIGIT_PAD = 540.dp
private val LETTER_PAD = 760.dp
