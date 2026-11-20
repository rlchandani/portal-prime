/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.CompositionLocalProvider
import com.immortal.launcher.jarvis.LocalJarvisPulse
import com.immortal.launcher.jarvis.rememberJarvisTarget

import com.immortal.launcher.jarvis.HearthpaneScreens
import com.immortal.launcher.jarvis.ScreenContext
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Calls app (absorbs Home's old Calls tile): rooms in this home with presence, Recent (missed
 * and history), Call everyone, Settings, and a way into Portal's own Messenger/WhatsApp calling.
 */
class CallsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val start = when (intent.getStringExtra(EXTRA_TAB)) {
      TAB_RECENT -> CallsTab.RECENT
      TAB_MESSAGES -> CallsTab.MESSAGES
      else -> CallsTab.ROOMS
    }
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        // Grandma mode replaces the whole app with big faces (set up on this Portal only).
        val rules by CallAutoAnswerStore.flow(this).collectAsState()
        if (rules.grandmaMode) CallsGrandmaHome() else CallsApp(start, onBack = ::finish)
      }
    }
    // Floating Jarvis: the rooms and recent calls are what `screen_context` describes here.
    HearthpaneScreens.track(this, HearthpaneScreens.Entry({ HearthpaneScreens.CALLS }) {
      ScreenContext.calls(CallingStore.peers.value, CallingStore.history.value, Calling.state.value.busy,
          System.currentTimeMillis())
    })
  }

  override fun onResume() {
    super.onResume()
    applyToolsDisplayBars(this)
  }

  companion object {
    const val EXTRA_TAB = "tab"
    const val TAB_RECENT = "recent"
    const val TAB_MESSAGES = "messages"

    fun open(context: Context, recent: Boolean = false) {
      runCatching {
        context.startActivity(Intent(context, CallsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { if (recent) putExtra(EXTRA_TAB, TAB_RECENT) })
      }
    }
  }
}

/** Unseen counts on the tabs: "Recent · 2", "Messages · 1". */
internal data class CallsBadges(val missed: Int, val messages: Int) {
  fun label(tab: CallsTab): String = tab.label + when {
    tab == CallsTab.RECENT && missed > 0 -> " · $missed"
    tab == CallsTab.MESSAGES && messages > 0 -> " · $messages"
    else -> ""
  }
}

internal enum class CallsTab(val label: String) { ROOMS("Rooms"), RECENT("Recent"), MESSAGES("Messages") }

/** Page surfaces from the refined Home palette (light or dark, as Settings › Display says). */
@Composable
internal fun callsSurface(): CallSurface {
  val p = LocalApprovedPalette.current
  return CallSurface(p.surface, p.text, p.outline, p.accent, if (p.dark) Color(0xFF14161B) else Color.White)
}

@Composable
private fun CallsApp(start: CallsTab, onBack: () -> Unit) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  var tab by rememberSaveable { mutableStateOf(start) }
  var everyone by remember { mutableStateOf(false) }
  var loopback by remember { mutableStateOf(false) }
  var testMessage by remember { mutableStateOf(false) }
  val history by CallingStore.history.collectAsState()
  // Index read + prune touch disk: off the main thread; the list fills in via the StateFlow.
  LaunchedEffect(Unit) { withContext(Dispatchers.IO) { CallMessageStore.ensureLoaded(context) } }
  val messages by CallMessageStore.messages.collectAsState()
  // While the app is open, ask the rooms who's around every 30 s (presence goes stale after 90 s).
  LaunchedEffect(Unit) {
    CallingStore.importDinnerRoster(context)
    while (true) { Calling.refreshPresence(context); delay(PRESENCE_POLL_MS) }
  }
  LaunchedEffect(tab) { if (tab == CallsTab.RECENT) CallingStore.markSeen(context) }
  // Jarvis pointing at a room or its Video call button (`hearthpane.highlight`): show the rooms.
  val jarvisTarget = rememberJarvisTarget(HearthpaneScreens.CALLS)
  LaunchedEffect(jarvisTarget) { if (jarvisTarget != null) tab = CallsTab.ROOMS }
  Box(Modifier.fillMaxSize().background(p.background)) {
    Column(Modifier.fillMaxSize()) {
      CallsHeader(tab, CallsBadges(CallHistory.unseenMissed(history), CallMessageList.unseen(messages)), onTab = { tab = it },
          onBack = onBack, onLoopback = { loopback = true }, onTestMessage = { testMessage = true })
      when (tab) {
        CallsTab.ROOMS -> CompositionLocalProvider(LocalJarvisPulse provides jarvisTarget) {
          CallsRooms(onEveryone = { everyone = true })
        }
        CallsTab.RECENT -> CallsRecent(history)
        CallsTab.MESSAGES -> CallMessagesTab()
      }
    }
    if (everyone) Box(Modifier.fillMaxSize()) { DinnerCallSheet(onDismiss = { everyone = false }) }
    if (loopback && BuildConfig.PORTAL_CALL_LOOPBACK) CallLoopbackScreen(onClose = { loopback = false })
    if (testMessage && BuildConfig.PORTAL_CALL_LOOPBACK) CallMessageTestSheet(onDismiss = { testMessage = false })
  }
}

@Composable
private fun CallsHeader(tab: CallsTab, badges: CallsBadges, onTab: (CallsTab) -> Unit, onBack: () -> Unit, onLoopback: () -> Unit,
                        onTestMessage: () -> Unit) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val surface = callsSurface()
  if (currentLayoutClass().compact) {
    CallsHeaderCompact(tab, badges, onTab, onBack, onLoopback, onTestMessage)
    return
  }
  Row(Modifier.fillMaxWidth().height(130.dp).padding(horizontal = 56.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    CallRoundButton("back", "Back", onBack, surface = surface)
    Column {
      Text("Calls", color = p.text, fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
      Text("Rooms in this home · end-to-end encrypted", color = p.secondary, fontSize = 24.sp)
    }
    Spacer(Modifier.weight(1f))
    CallsSegmented(CallsTab.entries.map(badges::label),
        tab.ordinal) { onTab(CallsTab.entries[it]) }
    if (BuildConfig.PORTAL_CALL_LOOPBACK) {
      CallPill("Loopback test", onLoopback, icon = "video", surface = surface, size = CallButtonSize.SM)
      CallPill("Test message", onTestMessage, icon = "msg", surface = surface, size = CallButtonSize.SM)
    }
    CallPill("Messenger & WhatsApp", { PortalHomeBridge.launch(context) }, icon = "phone", surface = surface, size = CallButtonSize.SM)
    CallRoundButton("gear", "Call settings", { context.startActivity(settingsPageIntent(context, SettingsPageIds.CALLS)) },
        surface = surface)
  }
}

/**
 * Compact header (the Mini, either way up): back, title and settings on the first row; the
 * Rooms/Recent tabs and the Messenger hand-off on the second (scrolls sideways if it must).
 */
@Composable
private fun CallsHeaderCompact(tab: CallsTab, badges: CallsBadges, onTab: (CallsTab) -> Unit, onBack: () -> Unit, onLoopback: () -> Unit,
                               onTestMessage: () -> Unit) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val surface = callsSurface()
  Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      CallRoundButton("back", "Back", onBack, surface = surface)
      Column(Modifier.weight(1f)) {
        Text("Calls", color = p.text, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, maxLines = 1)
        Text("Rooms in this home · end-to-end encrypted", color = p.secondary, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      CallRoundButton("gear", "Call settings", { context.startActivity(settingsPageIntent(context, SettingsPageIds.CALLS)) },
          surface = surface)
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      CallsSegmented(CallsTab.entries.map(badges::label),
          tab.ordinal) { onTab(CallsTab.entries[it]) }
      if (BuildConfig.PORTAL_CALL_LOOPBACK) {
      CallPill("Loopback test", onLoopback, icon = "video", surface = surface, size = CallButtonSize.SM)
      CallPill("Test message", onTestMessage, icon = "msg", surface = surface, size = CallButtonSize.SM)
    }
      CallPill("Messenger & WhatsApp", { PortalHomeBridge.launch(context) }, icon = "phone", surface = surface, size = CallButtonSize.SM)
    }
  }
}

/** `.seg`: surf2 track, white pill on the selected item. */
@Composable
internal fun CallsSegmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
  val p = LocalApprovedPalette.current
  Row(Modifier.background(p.surface2, RoundedCornerShape(50)).padding(6.dp)) {
    labels.forEachIndexed { i, label ->
      val on = i == selected
      Box(Modifier.height(76.dp).background(if (on) p.surface else Color.Transparent, RoundedCornerShape(50))
          .callClick(label, true) { onSelect(i) }.padding(horizontal = 34.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (on) p.text else p.secondary, fontSize = 25.5.sp, fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

/** A centred empty/error state card for the Calls app. */
@Composable
internal fun CallsNotice(icon: String, title: String, body: String, action: (@Composable () -> Unit)? = null) {
  val p = LocalApprovedPalette.current
  // Compact: as wide as the window allows (at most the designed 980), scrolling if a short window needs it.
  val frame = if (currentLayoutClass().compact)
    Modifier.padding(horizontal = 32.dp, vertical = 24.dp).widthIn(max = 980.dp).fillMaxWidth().verticalScroll(rememberScrollState())
  else Modifier.width(980.dp)
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(frame.callPanel(p.surface, RoundedCornerShape(32.dp), p.outline).padding(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
      CallIcon(icon, p.secondary, 64.dp)
      Text(title, color = p.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
      Text(body, color = p.secondary, fontSize = 26.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
      action?.invoke()
    }
  }
}

private const val PRESENCE_POLL_MS = 30_000L
