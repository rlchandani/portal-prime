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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/*
 * Linking another home (slice S2 of docs/plans/calling-phase-2.md), drawn from the approved mock's
 * `inviteScr`, `verifyScr` and `mismatchScr` (1 rem = 10 dp at 1920×1280). Stateless: the caller
 * owns the [HomeLinkInviterState] / [HomeLinkJoinerState] and passes callbacks. Light and dark
 * follow the page palette; every target is ≥ 72 dp.
 */

/** `.hdr`: 130 dp, a back button, the title and an optional line under it. */
@Composable
internal fun HomeLinkHeader(title: String, sub: String? = null, onBack: (() -> Unit)? = null) {
  val p = LocalApprovedPalette.current
  Row(Modifier.fillMaxWidth().height(130.dp).padding(horizontal = 56.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    onBack?.let { CallRoundButton("back", "Back", it, surface = callsSurface()) }
    Column(Modifier.semantics { heading() }) {
      Text(title, color = p.text, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      sub?.let { Text(it, color = p.secondary, fontSize = 22.sp, maxLines = 1) }
    }
  }
}

/** Mock `inviteScr`: the QR on the left; the code, its limits and the why on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeLinkInviteScreen(invite: HomeLinkInvite, now: Long, qr: Bitmap?, onNewCode: () -> Unit, onCancel: () -> Unit) {
  val p = LocalApprovedPalette.current
  Column(Modifier.fillMaxSize().background(p.background)) {
    HomeLinkHeader(HomeLinkCopy.INVITE_TITLE, HomeLinkCopy.INVITE_SUB, onBack = onCancel)
    Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(60.dp)) {
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        HomeLinkQr(qr, HomeLinkCopy.qrText(invite.code))
        HomeLinkLockedNote(HomeLinkCopy.INVITE_SCAN)
      }
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Text(HomeLinkCopy.INVITE_H2, color = p.text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text(HomeLinkCopy.INVITE_HOW, color = p.secondary, fontSize = 24.sp)
        HomeLinkSlots(invite.code.value.toList(), cursor = -1, spoken = "Code ${invite.code.value.toList().joinToString(" ")}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
          CallChip(HomeLinkCopy.expires(invite, now), "clock", plainFill = p.surface2, plainInk = p.secondary)
          CallChip(HomeLinkCopy.INVITE_ONCE, "key", plainFill = p.surface2, plainInk = p.secondary)
          CallChip(HomeLinkCopy.INVITE_READ, "phone", plainFill = p.surface2, plainInk = p.secondary)
        }
        HomeLinkNoteCard(HomeLinkCopy.INVITE_NOTE)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
          CallPill(HomeLinkCopy.NEW_CODE, onNewCode, icon = "redo", surface = callsSurface())
          CallPill(HomeLinkCopy.CANCEL, onCancel, surface = callsSurface())
        }
      }
    }
  }
}

/** Mock `verifyScr`: four pictures, twelve digits, and the two answers. */
@Composable
internal fun HomeLinkVerifyScreen(theirName: String, safety: HomeLinkSafetyNumber, onMatch: () -> Unit, onMismatch: () -> Unit) {
  val p = LocalApprovedPalette.current
  Column(Modifier.fillMaxSize().background(p.background)) {
    HomeLinkHeader(HomeLinkCopy.verifyTitle(theirName))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 56.dp, end = 56.dp, bottom = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterVertically)) {
      Text(HomeLinkCopy.VERIFY_LEDE, color = p.secondary, fontSize = 30.sp, textAlign = TextAlign.Center,
          lineHeight = 42.sp, modifier = Modifier.widthIn(max = 1500.dp))
      HomeLinkSafetyGrid(safety)
      Text(safety.displayDigits, color = p.text, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
          letterSpacing = 5.sp)
      Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        CallPill(HomeLinkCopy.NO_MATCH, onMismatch, icon = "x", size = CallButtonSize.LG, surface = callsSurface())
        CallPill(HomeLinkCopy.MATCH, onMatch, icon = "check", style = CallButtonStyle.OK, size = CallButtonSize.LG, surface = callsSurface())
      }
    }
  }
}

/** `.safety`: four cards across, each a big picture over its word. Read out as one sentence. */
@Composable
internal fun HomeLinkSafetyGrid(safety: HomeLinkSafetyNumber) {
  val p = LocalApprovedPalette.current
  BoxWithConstraints(Modifier.widthIn(max = 1400.dp).fillMaxWidth().clearAndSetSemantics { contentDescription = safety.spoken }) {
    val compact = maxWidth < COMPACT_GRID
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      safety.pictures.forEach { pic ->
        Column(Modifier.weight(1f).background(p.surface, RoundedCornerShape(30.dp)).border(1.5.dp, p.divider, RoundedCornerShape(30.dp))
            .padding(vertical = if (compact) 18.dp else 28.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(pic.emoji, fontSize = if (compact) 56.sp else 80.sp, lineHeight = if (compact) 62.sp else 88.sp)
          Text(pic.word, color = p.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
      }
    }
  }
}

/** Mock `mismatchScr` (and the expired / tampered variants): nothing kept, start again. */
@Composable
internal fun HomeLinkFailureScreen(failure: HomeLinkFailure, onStartAgain: () -> Unit, onClose: () -> Unit) {
  val p = LocalApprovedPalette.current
  val words = HomeLinkCopy.failure(failure)
  val icon = if (failure == HomeLinkFailure.EXPIRED) "clock" else "shield"
  Box(Modifier.fillMaxSize().background(p.background).padding(horizontal = 56.dp), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 1300.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(30.dp)) {
      Box(Modifier.size(200.dp).background(p.error.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        CallIcon(icon, p.error, 96.dp)
      }
      Text(words.title, color = p.text, fontSize = 60.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
          modifier = Modifier.semantics { heading() })
      Text(words.body, color = p.secondary, fontSize = 30.sp, lineHeight = 42.sp, textAlign = TextAlign.Center)
      Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        CallPill(HomeLinkCopy.START_AGAIN, onStartAgain, icon = "redo", style = CallButtonStyle.PRIMARY, size = CallButtonSize.LG,
            surface = callsSurface())
        CallPill(HomeLinkCopy.CLOSE, onClose, size = CallButtonSize.LG, surface = callsSurface())
      }
    }
  }
}

/** Mock `inviteDoneScr`: both homes joined by a locked dashed line. */
@Composable
internal fun HomeLinkConnectedScreen(theirName: String, onDone: () -> Unit) {
  val p = LocalApprovedPalette.current
  Box(Modifier.fillMaxSize().background(p.background).padding(horizontal = 56.dp), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 1400.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
        CallRoomAvatar("This home", 200.dp)
        Box(Modifier.size(240.dp, 80.dp), contentAlignment = Alignment.Center) {
          Box(Modifier.fillMaxWidth().height(4.dp).background(CallColors.Ok.copy(alpha = 0.6f)))
          Box(Modifier.size(80.dp).background(CallColors.Ok, CircleShape), contentAlignment = Alignment.Center) {
            CallIcon("lock", Color.White, 40.dp)
          }
        }
        CallRoomAvatar(theirName, 200.dp)
      }
      Text(HomeLinkCopy.connectedTitle(theirName), color = p.text, fontSize = 60.sp, fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
      Text(HomeLinkCopy.connectedBody(theirName), color = p.secondary, fontSize = 30.sp, lineHeight = 42.sp, textAlign = TextAlign.Center)
      CallPill(HomeLinkCopy.DONE, onDone, icon = "check", style = CallButtonStyle.PRIMARY, size = CallButtonSize.LG, surface = callsSurface())
    }
  }
}

/** `.slots`: one tile per symbol, a dash between groups of three; [cursor] gets the accent ring. */
@Composable
internal fun HomeLinkSlots(symbols: List<Char?>, cursor: Int, spoken: String) {
  val p = LocalApprovedPalette.current
  val shape = RoundedCornerShape(20.dp)
  Row(Modifier.clearAndSetSemantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically) {
    symbols.forEachIndexed { i, c ->
      if (i > 0 && i % SLOT_GROUP == 0) Text("–", color = p.secondary, fontSize = 50.sp, modifier = Modifier.padding(horizontal = 4.dp))
      Box(Modifier.size(70.dp, 100.dp).background(p.surface, shape)
          .border(if (i == cursor) 4.dp else 2.dp, if (i == cursor) p.accent else p.outline, shape), contentAlignment = Alignment.Center) {
        Text(c?.toString() ?: "", color = p.text, fontSize = 50.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
      }
    }
  }
}

/** `.qr`: 460 dp on white so phones scan it in a dark room too. */
@Composable
private fun HomeLinkQr(qr: Bitmap?, text: String) {
  Box(Modifier.size(460.dp).background(Color.White, RoundedCornerShape(30.dp)).padding(24.dp)
      .clearAndSetSemantics { contentDescription = "QR code for Portal Toolkit" }, contentAlignment = Alignment.Center) {
    if (qr != null) Image(qr.asImageBitmap(), contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
    else Text(text, color = Color(0xFF182C39), fontSize = 20.sp, textAlign = TextAlign.Center)
  }
}

/** The lock line under the QR (mock `e2e()`). */
@Composable
private fun HomeLinkLockedNote(text: String) {
  val p = LocalApprovedPalette.current
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    CallIcon("lock", p.green, 28.dp)
    Text(text, color = p.secondary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
  }
}

/** `.card` with a shield: the one-line why. */
@Composable
internal fun HomeLinkNoteCard(text: String) {
  val p = LocalApprovedPalette.current
  val shape = RoundedCornerShape(32.dp)
  Row(Modifier.fillMaxWidth().background(p.surface, shape).border(1.5.dp, p.divider, shape).padding(horizontal = 32.dp, vertical = 28.dp),
      horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    CallIcon("shield", p.text, 34.dp)
    Text(text, color = p.text, fontSize = 24.sp, lineHeight = 34.sp)
  }
}

private const val SLOT_GROUP = 3
private val COMPACT_GRID = 900.dp
