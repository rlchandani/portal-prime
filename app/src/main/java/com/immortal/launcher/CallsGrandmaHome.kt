/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/**
 * Hooks other Calling slices fill in for Grandma mode. The messages slice sets [openMessages]
 * (its "Missed & messages" screen) and [unseen] (new messages + missed calls). Until then the
 * Grandma home opens Recent and counts unseen missed calls.
 */
internal object CallGrandmaHooks {
  @Volatile var openMessages: ((Context) -> Unit)? = null
  @Volatile var unseen: (Context) -> Int = { CallHistory.unseenMissed(CallingStore.history.value) }
}

/** A face on the Grandma home. */
@Immutable
internal data class GrandmaFace(val id: String, val name: String, val presence: CallPresence)

/** Who gets one of the four big faces. Pure. */
internal object GrandmaFaces {
  const val MAX = 4

  /** Favourite rooms first (then by name), then the other rooms, then phones open right now. */
  fun pick(peers: List<CallPeer>, phones: List<CallPhone>, nowMs: Long, max: Int = MAX): List<GrandmaFace> {
    val rooms = CallPeers.forHome(peers.filter { it.acceptsCalls }, max)
        .map { GrandmaFace(it.id, it.name, CallPolicy.presence(it, nowMs)) }
    val open = phones.filter { it.reachable(nowMs) }.sortedBy { it.name.lowercase() }
        .map { GrandmaFace(it.id, it.name, CallPresence.AVAILABLE) }
    return (rooms + open).distinctBy { it.id }.take(max)
  }
}

/**
 * The Calls app in Grandma mode (mock `gmHomeScr`): a huge clock, four huge faces, one messages
 * button, nothing else to get lost in. Settings hide behind a 5 s hold on the clock.
 */
@Composable
internal fun CallsGrandmaHome() {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val layout = currentLayoutClass()
  val sizes = GrandmaSizes.of(layout)
  val peers by CallingStore.peers.collectAsState()
  val phones by CallPhoneMailbox.phones.collectAsState()
  val history by CallingStore.history.collectAsState()
  var recent by remember { mutableStateOf(false) }
  val now = rememberCallClock(CLOCK_STEP_MS)
  LaunchedEffect(Unit) {
    CallingStore.importDinnerRoster(context)
    while (true) { Calling.refreshPresence(context); delay(PRESENCE_POLL_MS) }
  }
  if (recent) { GrandmaRecent(history, sizes) { recent = false }; return }
  val faces = remember(peers, phones, now / FACE_REFRESH_MS) { GrandmaFaces.pick(peers, phones, now) }
  val unseen = remember(history) { CallGrandmaHooks.unseen(context) }
  Column(Modifier.fillMaxSize().background(p.background).padding(horizontal = sizes.edge + 12.dp, vertical = sizes.edge)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
      GrandmaClock(now, Modifier.weight(1f)) { context.startActivity(settingsPageIntent(context, SettingsPageIds.CALLS)) }
      GrandmaButton(GrandmaCopy.messages(unseen), "msg", if (unseen > 0) p.accent else p.surface, if (unseen > 0) Color.White else p.text,
          sizes.copy(button = MESSAGE_BUTTON, buttonText = sizes.buttonText * 0.8f), onClick = {
            val open = CallGrandmaHooks.openMessages
            if (open != null) open(context) else recent = true
          }, edge = if (unseen > 0) null else p.outline)
    }
    Spacer(Modifier.height(sizes.gap + 8.dp))
    if (faces.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      Text("Rooms appear here once their Portals answer.", color = p.secondary, fontSize = 40.sp, textAlign = TextAlign.Center)
    } else GrandmaFaceGrid(faces, layout, sizes, Modifier.weight(1f).fillMaxWidth())
  }
}

@Composable
private fun GrandmaClock(now: Long, modifier: Modifier, onHeld: () -> Unit) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val compact = currentLayoutClass().compact
  val cal = remember(now / CLOCK_STEP_MS) { Calendar.getInstance().apply { timeInMillis = now } }
  val time = remember(cal.get(Calendar.MINUTE), cal.get(Calendar.HOUR_OF_DAY)) {
    DateFormat.format(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", cal).toString()
  }
  val day = DateFormat.format("EEEE", cal).toString() + " " + GrandmaCopy.partOfDay(cal.get(Calendar.HOUR_OF_DAY))
  Column(modifier.semantics { contentDescription = "$time, $day" }.pointerInput(Unit) {
    detectTapGestures(onPress = {
      // Hidden settings: only a long, deliberate hold opens them; a tap or brush does nothing.
      val released = withTimeoutOrNull(SETTINGS_HOLD_MS) { tryAwaitRelease() }
      if (released == null) onHeld()
    })
  }) {
    Text(time, color = p.text, fontSize = if (compact) 96.sp else 120.sp, fontWeight = FontWeight.Light, letterSpacing = (-3).sp,
        lineHeight = if (compact) 100.sp else 124.sp)
    Text(day, color = p.secondary, fontSize = if (compact) 34.sp else 44.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

/** Four across on a landscape window, two by two upright. Each face fills its cell. */
@Composable
private fun GrandmaFaceGrid(faces: List<GrandmaFace>, layout: LayoutClass, sizes: GrandmaSizes, modifier: Modifier) {
  val columns = if (layout.compactPortrait) 2 else GrandmaFaces.MAX
  BoxWithConstraints(modifier) {
    val rows = if (layout.compactPortrait) 2 else 1
    val cellW = (maxWidth - sizes.gap * (columns - 1)) / columns
    val cellH = (maxHeight - sizes.gap * (rows - 1)) / rows
    val avatar = minOf(cellW * AVATAR_OF_WIDTH, cellH * AVATAR_OF_HEIGHT, MAX_AVATAR)
    Column(verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
      faces.chunked(columns).forEach { line ->
        Row(Modifier.height(cellH), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
          line.forEach { GrandmaFaceCard(it, avatar, sizes, Modifier.width(cellW).height(cellH)) }
        }
      }
    }
  }
}

@Composable
private fun GrandmaFaceCard(face: GrandmaFace, avatar: androidx.compose.ui.unit.Dp, sizes: GrandmaSizes, modifier: Modifier) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val shape = RoundedCornerShape(sizes.radius)
  val compact = currentLayoutClass().compact
  Column(modifier.background(p.surface, shape).border(2.dp, p.outline, shape)
      .callClick("Call ${face.name}", true) { Calling.dial(context, face.id, CallKind.CALL, video = true) }.padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
    CallRoomAvatar(face.name, avatar, face.presence, ring = p.surface)
    Text(face.name, color = p.text, fontSize = if (compact) 40.sp else 50.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
    Row(Modifier.height(if (compact) 84.dp else 96.dp).background(CallColors.Ok, RoundedCornerShape(50)).padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      CallIcon("video", Color.White, if (compact) 36.dp else 40.dp)
      Text("Call", color = Color.White, fontSize = if (compact) 32.sp else 36.sp, fontWeight = FontWeight.Bold)
    }
  }
}

/** Until the messages slice provides its screen: Recent, with a big way back. */
@Composable
private fun GrandmaRecent(history: List<CallHistoryEntry>, sizes: GrandmaSizes, onBack: () -> Unit) {
  val p = LocalApprovedPalette.current
  Column(Modifier.fillMaxSize().background(p.background)) {
    Box(Modifier.padding(sizes.edge)) {
      GrandmaButton("Back", "back", p.surface, p.text, sizes, onBack, Modifier.width(360.dp), edge = p.outline)
    }
    Box(Modifier.weight(1f)) { CallsRecent(history) }
  }
}

private const val CLOCK_STEP_MS = 1_000L
private const val PRESENCE_POLL_MS = 30_000L
private const val FACE_REFRESH_MS = 5_000L
/** "Hold the clock for 5 seconds to get back here" (mock gmSetup). */
private const val SETTINGS_HOLD_MS = 5_000L
private const val AVATAR_OF_WIDTH = 0.72f
private const val AVATAR_OF_HEIGHT = 0.5f
private val MAX_AVATAR = 240.dp
private val MESSAGE_BUTTON = 120.dp
