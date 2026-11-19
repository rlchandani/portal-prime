/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlinx.coroutines.withTimeoutOrNull

/** A change waiting for the press-and-hold at this Portal. */
private sealed interface PendingArm {
  data object AutoAnswer : PendingArm
  data class Person(val id: String, val name: String) : PendingArm
}

/** Words for Settings › Calls › Grandma mode (pure). */
internal object GrandmaSettingsCopy {
  const val REFUSED = "The phone remote was used in the last 2 minutes. Put it down and touch this Portal’s own screen to turn this on."
  const val IN_PERSON = "Someone at this Portal must turn this on. Never from a phone, the phone remote or another Portal."

  fun quietNote(quietOn: Boolean, startMin: Int, endMin: Int): String =
      if (quietOn) "Never auto-answers in quiet hours (${hm(startMin)}–${hm(endMin)}). The camera light and the live bar always show."
      else "Never auto-answers in quiet hours (turn them on in Sounds › Chimes). The camera light and the live bar always show."

  fun personSub(phone: Boolean, chosen: Boolean): String = when {
    chosen -> "Answers by itself · 10-second countdown, chime, Not now"
    phone -> "Phone · Portal Toolkit · always rings"
    else -> "Room in this home · always rings"
  }

  fun confirmTitle(pending: String?): String =
      if (pending == null) "Turn on auto-answer on this Portal?" else "Let calls from $pending answer by themselves here?"

  private fun hm(min: Int) = "%d:%02d".format(min / 60, min % 60)
}

/**
 * Settings › Calls › Grandma mode (mock `gmSetupScr` and Settings › Answering). Written only through
 * [CallAutoAnswerStore] from this screen; arming changes need a 2 s press-and-hold here and no
 * recent phone-remote input ([CallInPerson]).
 */
@Composable
internal fun CallGrandmaBlock() {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val rules by CallAutoAnswerStore.flow(context).collectAsState()
  val peers by CallingStore.peers.collectAsState()
  val phones by CallPhoneMailbox.phones.collectAsState()
  var pending by remember { mutableStateOf<PendingArm?>(null) }
  var refused by remember { mutableStateOf(false) }
  fun apply(edit: (AutoAnswerRules) -> AutoAnswerRules) { refused = !CallAutoAnswerStore.update(context, edit) }
  Card {
    GrandmaRow("Grandma mode", "Big faces, big buttons, nothing to get lost in. Calls and in-call show only Hang up, Quieter and Louder.",
        rules.grandmaMode) { on -> pending = null; apply { AutoAnswerEdits.grandma(it, on) } }
    Divider()
    GrandmaRow("Hide settings", "On the Calls screen, hold the clock for 5 seconds to get back here.", checked = null) {}
  }
  if (!rules.grandmaMode) return
  Text("Answer by itself for", color = p.secondary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 10.dp))
  Card {
    GrandmaRow("Auto-answer for chosen family", "Off by default. A chime, a 10-second countdown and a big Not now, then it connects.",
        rules.enabled) { on -> if (on) pending = PendingArm.AutoAnswer else { pending = null; apply { AutoAnswerEdits.enabled(it, false) } } }
    val candidates = remember(peers, phones) {
      peers.map { Triple(it.id, it.name, false) } + phones.map { Triple(it.id, it.name, true) }
    }.distinctBy { it.first }
    candidates.forEach { (id, name, phone) ->
      Divider()
      val chosen = id in rules.allowed
      Box(Modifier.alpha(if (rules.enabled) 1f else DIMMED)) {
        GrandmaRow(name, GrandmaSettingsCopy.personSub(phone, chosen), chosen, enabled = rules.enabled, leading = {
          CallRoomAvatar(name, 72.dp)
        }) { on -> if (on) pending = PendingArm.Person(id, name) else apply { AutoAnswerEdits.allow(it, id, false) } }
      }
    }
    if (candidates.isEmpty()) {
      Divider()
      Text("Rooms and open phones appear here once they’ve been heard.", color = p.secondary, fontSize = 22.sp,
          modifier = Modifier.padding(28.dp))
    }
  }
  pending?.let { arm ->
    ConfirmInPerson(GrandmaSettingsCopy.confirmTitle((arm as? PendingArm.Person)?.name), onCancel = { pending = null }) {
      pending = null
      apply { r ->
        when (arm) {
          PendingArm.AutoAnswer -> AutoAnswerEdits.enabled(r, true)
          is PendingArm.Person -> AutoAnswerEdits.allow(r, arm.id, true)
        }
      }
    }
  }
  if (refused) NoteCard("warn", GrandmaSettingsCopy.REFUSED, CallColors.Busy)
  val chime = remember { ChimeConfig.load(context) }
  NoteCard("moon", GrandmaSettingsCopy.quietNote(chime.quietHoursOn, chime.quietStartMin, chime.quietEndMin), p.secondary)
  NoteCard("lock", GrandmaSettingsCopy.IN_PERSON, p.secondary)
}

/** Settings › Calls › Phones: the paired phones this Portal has heard, honestly. */
@Composable
internal fun CallPhonesBlock() {
  val p = LocalApprovedPalette.current
  val phones by CallPhoneMailbox.phones.collectAsState()
  val now = rememberCallClock(PHONES_REFRESH_MS)
  Card {
    if (phones.isEmpty()) Text("No phone has opened Portal Toolkit since this Portal started.", color = p.secondary,
        fontSize = 22.sp, modifier = Modifier.padding(28.dp))
    phones.forEachIndexed { i, phone ->
      if (i > 0) Divider()
      val open = phone.reachable(now)
      GrandmaRow(phone.name, CallPhonesCopy.state(open, now - phone.seenMs), checked = null, leading = {
        CallRoomAvatar(phone.name, 72.dp, if (open) CallPresence.AVAILABLE else CallPresence.OFFLINE, ring = p.surface)
      }) {}
    }
  }
  FooterNote(CallPhonesCopy.NOTE)
}

internal object CallPhonesCopy {
  const val NOTE = "A Portal can ring a phone only while Portal Toolkit is open on it. Ringing a phone in your pocket comes in a later update."

  fun state(open: Boolean, agoMs: Long): String = if (open) "Portal Toolkit is open · this Portal can ring it"
  else "Not open · last heard ${ago(agoMs)}"

  private fun ago(ms: Long): String {
    val min = ms / 60_000L
    return when {
      min < 1 -> "just now"
      min < 60 -> "$min min ago"
      else -> "${min / 60} h ago"
    }
  }
}

@Composable
private fun GrandmaRow(title: String, help: String, checked: Boolean?, enabled: Boolean = true,
                       leading: (@Composable () -> Unit)? = null, onChange: (Boolean) -> Unit) {
  val toggle = if (checked != null && enabled) Modifier.semantics { role = Role.Switch; contentDescription = title }
      .callClick(title, true) { onChange(!checked) } else Modifier
  Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight).then(toggle).padding(horizontal = 28.dp, vertical = 22.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    leading?.invoke()
    Column(Modifier.weight(1f)) {
      Text(title, color = IosLabel, fontSize = Sizes.rowTitle, lineHeight = 34.sp)
      Text(help, color = IosSecondary, fontSize = Sizes.rowSub, lineHeight = 28.sp, modifier = Modifier.padding(top = 8.dp))
    }
    if (checked != null) ApprovedSettingsSwitch(checked, enabled = enabled)
  }
}

/** The in-person step: a 2 s press-and-hold that fills as it is held. A tap does nothing. */
@Composable
private fun ConfirmInPerson(title: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
  val p = LocalApprovedPalette.current
  var holding by remember { mutableStateOf(false) }
  val fill by animateFloatAsState(if (holding) 1f else 0f, tween(if (holding) CallInPerson.HOLD_MS.toInt() else RESET_MS), label = "hold")
  Column(Modifier.padding(top = 18.dp).fillMaxWidth().background(p.surface, RoundedCornerShape(Sizes.cardRadius))
      .border(2.dp, p.accent.copy(alpha = 0.5f), RoundedCornerShape(Sizes.cardRadius)).padding(28.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(title, color = p.text, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold)
    Text("${GrandmaSettingsCopy.IN_PERSON} It never answers in quiet hours, for drop-ins, or after Not now for 30 minutes.",
        color = p.secondary, fontSize = Sizes.rowSub, lineHeight = 28.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.weight(1f).height(Sizes.primaryTarget).background(p.surface2, RoundedCornerShape(50))
          .semantics { role = Role.Button; contentDescription = "Hold for 2 seconds to turn on" }
          .pointerInput(Unit) {
            detectTapGestures(onPress = {
              holding = true
              val released = withTimeoutOrNull(CallInPerson.HOLD_MS) { tryAwaitRelease() }
              holding = false
              if (released == null) onConfirm()
            })
          }) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(CallColors.Ok, RoundedCornerShape(50)))
        Text(if (holding) "Keep holding…" else "Hold for 2 seconds to turn on", color = if (fill > HALF) Color.White else p.text,
            fontSize = Sizes.actionButton, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
      }
      CallPill("Cancel", onCancel, size = CallButtonSize.SM, surface = callsSurface())
    }
  }
}

@Composable
private fun NoteCard(icon: String, text: String, tint: Color) {
  val p = LocalApprovedPalette.current
  Row(Modifier.padding(top = 18.dp).fillMaxWidth().background(p.surface, RoundedCornerShape(Sizes.cardRadius))
      .border(1.5.dp, p.outline, RoundedCornerShape(Sizes.cardRadius)).padding(horizontal = 28.dp, vertical = 24.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    CallIcon(icon, tint, 34.dp)
    Text(text, color = p.text, fontSize = Sizes.rowSub, lineHeight = 28.sp)
  }
}

private const val DIMMED = 0.45f
private const val RESET_MS = 150
private const val HALF = 0.5f
private const val PHONES_REFRESH_MS = 5_000L
