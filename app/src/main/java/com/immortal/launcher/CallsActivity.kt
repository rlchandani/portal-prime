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
import androidx.compose.ui.platform.LocalConfiguration
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
  LaunchedEffect(Unit) { CallMessageStore.ensureLoadedOffMain(context) }
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
    if (BuildConfig.PORTAL_CALL_LOOPBACK) {
      // Measurement builds: the test pills don't fit beside the tabs, so this part scrolls
      // sideways instead of squeezing the last pill and pushing the gear off screen.
      Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        CallsSegmented(CallsTab.entries.map(badges::label), tab.ordinal) { onTab(CallsTab.entries[it]) }
        CallsTestPills(onLoopback, onTestMessage, surface)
        CallPill("Messenger & WhatsApp", { PortalHomeBridge.launch(context) }, icon = "phone", surface = surface, size = CallButtonSize.SM)
      }
    } else {
      Spacer(Modifier.weight(1f))
      CallsSegmented(CallsTab.entries.map(badges::label),
          tab.ordinal) { onTab(CallsTab.entries[it]) }
      CallPill("Messenger & WhatsApp", { PortalHomeBridge.launch(context) }, icon = "phone", surface = surface, size = CallButtonSize.SM)
    }
    CallRoundButton("gear", "Call settings", { context.startActivity(settingsPageIntent(context, SettingsPageIds.CALLS)) },
        surface = surface)
  }
}

/**
 * Compact header (the Mini, either way up): back, title and settings on the first row; below it the
 * tabs and the Messenger hand-off in full-width rows that never scroll sideways ([CallsHeaderLayout]).
 * Upright the tabs span the width with the hand-off on its own row; turned they share one row.
 */
@Composable
private fun CallsHeaderCompact(tab: CallsTab, badges: CallsBadges, onTab: (CallsTab) -> Unit, onBack: () -> Unit, onLoopback: () -> Unit,
                               onTestMessage: () -> Unit) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val surface = callsSurface()
  val configuration = LocalConfiguration.current
  val labels = CallsTab.entries.map(badges::label)
  val rows = CallsHeaderLayout.rows(currentLayoutClass(), configuration.screenWidthDp, labels,
      BuildConfig.PORTAL_CALL_LOOPBACK, configuration.fontScale).orEmpty()
  Column(Modifier.fillMaxWidth().padding(horizontal = CallsHeaderLayout.SIDE_PADDING_DP.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      CallRoundButton("back", "Back", onBack, surface = surface)
      Column(Modifier.weight(1f)) {
        Text("Calls", color = p.text, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, maxLines = 1)
        Text("Rooms in this home · end-to-end encrypted", color = p.secondary, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      CallRoundButton("gear", "Call settings", { context.startActivity(settingsPageIntent(context, SettingsPageIds.CALLS)) },
          surface = surface)
    }
    rows.forEach { row ->
      val hasTabs = CallsHeaderLayout.Item.TABS in row
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(CallsHeaderLayout.GAP_DP.dp)) {
        row.forEach { item ->
          // With the tabs in the row they take the rest; without them the pills share it equally.
          val share = if (hasTabs) Modifier else Modifier.weight(1f)
          when (item) {
            CallsHeaderLayout.Item.TABS -> CallsSegmented(labels, tab.ordinal, Modifier.weight(1f),
                CallsHeaderLayout.tabWeights(labels,
                    CallsHeaderLayout.tabsTrackDp(row, configuration.screenWidthDp, labels, configuration.fontScale),
                    configuration.fontScale)) { onTab(CallsTab.entries[it]) }
            CallsHeaderLayout.Item.LOOPBACK_TEST -> CallPill(CallsHeaderLayout.LOOPBACK_LABEL, onLoopback, share, icon = "video",
                surface = surface, size = CallButtonSize.SM)
            CallsHeaderLayout.Item.TEST_MESSAGE -> CallPill(CallsHeaderLayout.TEST_MESSAGE_LABEL, onTestMessage, share, icon = "msg",
                surface = surface, size = CallButtonSize.SM)
            CallsHeaderLayout.Item.HANDOFF -> CallPill(CallsHeaderLayout.HANDOFF_LABEL, { PortalHomeBridge.launch(context) }, share,
                icon = "phone", surface = surface, size = CallButtonSize.SM)
          }
        }
      }
    }
  }
}

/** Measurement builds only: the loopback call and test message entries. */
@Composable
private fun CallsTestPills(onLoopback: () -> Unit, onTestMessage: () -> Unit, surface: CallSurface) {
  CallPill("Loopback test", onLoopback, icon = "video", surface = surface, size = CallButtonSize.SM)
  CallPill("Test message", onTestMessage, icon = "msg", surface = surface, size = CallButtonSize.SM)
}

/**
 * Calls tabs (Rooms / Recent / Messages). A thin adapter over the shared [HearthSegmented] with the
 * neutral variant: the selected pill now glides and gains the unified rounded-rect + depth. [weights]
 * still stretches the tabs across the compact header; null keeps their natural widths. The existing
 * `callClick` button semantics are preserved verbatim.
 */
@Composable
internal fun CallsSegmented(labels: List<String>, selected: Int, modifier: Modifier = Modifier,
                            /** Stretched (compact): one weight per tab; null keeps the natural widths. */
                            weights: List<Float>? = null, onSelect: (Int) -> Unit) {
  HearthSegmented(
      segments = labels.map { HearthSegment(it) },
      selectedIndex = selected,
      colors = HearthSegmentedDefaults.colors(LocalApprovedPalette.current),
      modifier = modifier,
      weights = weights,
      cellModifier = { i, _, label -> Modifier.callClick(label, true) { onSelect(i) } })
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
