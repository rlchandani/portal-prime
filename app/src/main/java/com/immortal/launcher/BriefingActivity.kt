/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The full-screen morning briefing. Opened from the Good morning card on Home, or by
 * [BriefingLauncher.afterWake] when the alarm / sunrise light is turned off. All data loads off
 * the main thread; the first frame is just the greeting.
 */
class BriefingActivity : ComponentActivity() {
  private var stopSpeech: () -> Unit = {}

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // It can follow the alarm over the lock screen.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        BackHandler(onBack = ::dismiss)
        BriefingHost(personId = intent.getStringExtra(EXTRA_PERSON), onDismiss = ::dismiss, onSpeak = ::speak)
      }
    }
  }

  override fun onStop() {
    // Leaving the card (Home, another app, screen off) ends the reading too.
    stopSpeech()
    super.onStop()
  }

  private fun speak(text: String) {
    stopSpeech()
    stopSpeech = ChimePlayer.speakInChimeVoice(this, text)
  }

  private fun dismiss() {
    stopSpeech()
    val now = System.currentTimeMillis()
    BriefingDayMarks.markDismissed(this, BriefingPolicy.localDay(now, TimeZone.getDefault()))
    finish()
  }

  companion object {
    /** Who's there? person id: the briefing is theirs while their Home is showing ([BriefingPersonalPolicy]). */
    private const val EXTRA_PERSON = "person"

    fun open(context: Context, personId: String? = null) {
      runCatching {
        context.startActivity(Intent(context, BriefingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { personId?.let { putExtra(EXTRA_PERSON, it) } })
      }.onFailure { Log.w("ImmortalBriefing", "briefing could not open", it) }
    }
  }
}

/** Refresh the clock line and timers while the card is open. */
private const val TICK_MS = 30_000L
/** Read aloud once both slow sources answered, or after this long with whatever arrived. */
private const val SPEAK_WAIT_MS = 12_000L
/** The card closes by itself so the screen doesn't stay on all morning. */
private const val AUTO_CLOSE_MS = 10 * 60_000L

@androidx.compose.runtime.Composable
private fun BriefingHost(personId: String?, onDismiss: () -> Unit, onSpeak: (String) -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var inputs by remember { mutableStateOf<BriefingInputs?>(null) }
  val personal = rememberBriefingPersonal(personId)
  var spoken by rememberSaveable { mutableStateOf(false) }
  var speakTimedOut by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    inputs = withContext(Dispatchers.IO) { BriefingLoader.local(context, System.currentTimeMillis()) }
    launch {
      val weather = withContext(Dispatchers.IO) { BriefingLoader.weather(context) }
      inputs = inputs?.copy(weather = BriefingPart.Ready(weather))
    }
    launch {
      val events = withContext(Dispatchers.IO) { BriefingLoader.events(context, System.currentTimeMillis()) }
      inputs = inputs?.copy(events = BriefingPart.Ready(events))
    }
    launch { delay(SPEAK_WAIT_MS); speakTimedOut = true }
    launch { delay(AUTO_CLOSE_MS); onDismiss() }
    while (true) {
      delay(TICK_MS)
      inputs = inputs?.copy(nowMs = System.currentTimeMillis())
    }
  }
  val briefing = remember(inputs, personal) { inputs?.copy(personal = personal)?.let(BriefingBuilder::build) }
  LaunchedEffect(briefing?.complete, speakTimedOut) {
    val text = briefing?.spoken ?: return@LaunchedEffect
    if (spoken || !(briefing.complete || speakTimedOut)) return@LaunchedEffect
    spoken = true
    onSpeak(text)
  }
  BriefingScreen(briefing, onDismiss)
}

/**
 * The personal part, live: null (the household briefing) unless Who's there? still shows [personId]'s Home.
 * Their events load once; their reminders come from the shared list; private items follow
 * [BriefingPersonalPolicy.hidePrivate], re-checked on every Who's there? change and when the hold ends.
 */
@androidx.compose.runtime.Composable
private fun rememberBriefingPersonal(personId: String?): BriefingPersonalInputs? {
  if (personId == null) return null
  val context = androidx.compose.ui.platform.LocalContext.current
  val ui by WhosThere.state.collectAsState()
  var holdOver by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { delay(BriefingPersonalPolicy.PRIVATE_HOLD_MS); holdOver = true }
  val owner = BriefingPersonalPolicy.owner(ui, personId) ?: return null
  var events by remember(owner.id) { mutableStateOf<BriefingPart<List<PersonalEvent>?>>(BriefingPart.Loading) }
  var reminders by remember(owner.id) { mutableStateOf(emptyList<String>()) }
  LaunchedEffect(owner.id) {
    reminders = withContext(Dispatchers.IO) {
      runCatching { WhosThereContent.reminders(HouseholdListStore.current(context).open().map { it.text.value }, owner) }
          .getOrElse { Log.w("ImmortalBriefing", "personal reminders unavailable", it); emptyList() }
    }
    val day = withContext(Dispatchers.IO) { loadWhosThereDay(context.applicationContext, owner, ui.people) }
    events = BriefingPart.Ready(day.events.takeIf { day.source != null })
  }
  return BriefingPersonalInputs(owner.name, BriefingPersonalPolicy.hidePrivate(ui, holdOver), events, reminders)
}

