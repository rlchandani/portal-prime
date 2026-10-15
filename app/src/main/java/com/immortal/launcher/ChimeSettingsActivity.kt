/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.PortalPrimeTheme

class ChimeSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { ChimeSettingsScreen() } }
  }
}

@Composable
private fun ChimeSettingsScreen() {
  val activity = LocalContext.current as? Activity
  SettingsScaffold(
      title = "Sounds",
      subtitle = "Gentle ambient cues. All off by default; nothing plays during quiet hours.",
      onBack = { activity?.finish() },
  ) { ChimeSettingsContent() }
}

/** Inlinable body of the Chime settings screen — rendered both by [ChimeSettingsScreen] (standalone
 *  Activity host) and by the two-panel settings detail panel. */
@Composable
internal fun ChimeSettingsContent() {
  val context = LocalContext.current
  val application = context.applicationContext
  val writes = remember(application) { ChimeSettingsWrites.queue(application) }
  var settings by remember(application) { mutableStateOf(ChimeConfig.load(application)) }
  var saveState by remember { mutableStateOf(ChimeSaveState()) }
  val applying = saveState.saving
  val failed = saveState.failed
  val completion = rememberCoroutineScope()
  val lifecycleOwner = LocalLifecycleOwner.current
  val pageActive = LocalApprovedLauncherPageActive.current
  suspend fun readSnapshot() = withContext(Dispatchers.IO) { ChimeConfig.load(application) }
  suspend fun publishAfterSave(revision: Int) {
    try {
      writes.awaitIdle()
      val loaded = readSnapshot()
      if (revision == saveState.revision) {
        settings = loaded
        saveState = saveState.completeRead(revision, writes.failureCount)
      }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { saveState = saveState.readFailed(revision) }
  }
  LaunchedEffect(application, lifecycleOwner, pageActive) {
    if (!pageActive) return@LaunchedEffect
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      saveState = saveState.beginRefresh()
      publishAfterSave(saveState.revision)
    }
  }
  fun audition(play: () -> Unit) {
    if (!saveState.saving) play()
  }
  fun apply(key: String, value: Any) {
    if (saveState.saving) return
    saveState = saveState.beginEdit(writes.failureCount)
    val revision = saveState.revision
    writes.submit(key, value)
    completion.launch { publishAfterSave(revision) }
  }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      // Scalar controls (toggles, volumes, quiet times, sunrise sound) render from the `chime`
      // registry domain - the same specs the phone remote uses. Apply routes through the domain so
      // its onApplied (ChimeScheduler.reschedule) fires here too, not just from the remote.
      Column(Modifier.semantics { contentDescription = when {
        applying -> "Saving Sounds settings"
        failed -> "Sounds save unavailable"
        else -> "Sounds settings ready"
      } }) {
        FooterNote(when {
          applying -> "Saving changes…"
          failed -> "Some changes couldn't be saved. Try again."
          else -> "Sounds respect your quiet hours."
        })
      }

      // Keep save feedback visible above the independently scrolling scalar list.
      SettingsList(SettingsDomains.chime, settings, exclude = setOf("spokenVoice"),
        editsEnabled = !applying, onApply = ::apply)

      // Bespoke: voice picker + test buttons the registry can't model (TTS voice enumeration is
      // on-device only; test buttons are actions, not settings).
      if (settings.spokenTimeOn || settings.hourlyChimeOn || settings.goldenHourOn) {
        Card {
          if (settings.spokenTimeOn) {
            VoicePicker(settings.spokenVoice, enabled = !applying) { name ->
              // Installed identities share the same save lock, worker and readback as other edits.
              apply("spokenVoice", name)
            }
            TestButton("Test voice", enabled = !applying) {
              audition { ChimePlayer.testVoice(context, settings.spokenVoice) }
            }
          }
          if (settings.hourlyChimeOn) {
            TestButton("Play chime", enabled = !applying) { audition { ChimePlayer.playChime(context) } }
          }
          if (settings.goldenHourOn) {
            TestButton("Test sunrise", enabled = !applying) { audition { ChimePlayer.playSunriseTone(context) } }
            TestButton("Test sunset", enabled = !applying) { audition { ChimePlayer.playSunsetTone(context) } }
          }
          TestButton("Play ping", enabled = !applying) { audition { ChimePlayer.playPing(context, repeats = 1) } }
        }
      }
    }
}

@Composable
private fun TestButton(label: String, enabled: Boolean, onClick: () -> Unit) {
  Surface(
      color = IosFocusTint,
      shape = RoundedCornerShape(12.dp),
      modifier =
          Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
              .tvFocusable(RoundedCornerShape(12.dp), focusScale = 1f, enabled = enabled) { onClick() },
  ) {
    Text(
        "▶  $label",
        color = if (enabled) IosBlue else IosSecondary,
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
    )
  }
}

/** Bounded chooser over the existing on-device engine. Empty identity means engine default. */
@Composable
private fun VoicePicker(current: String, enabled: Boolean, onPick: (String) -> Unit) {
  val context = LocalContext.current
  var voices by remember { mutableStateOf<List<InstalledVoiceChoice>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var loadFailed by remember { mutableStateOf(false) }
  var expanded by remember { mutableStateOf(false) }
  LaunchedEffect(enabled) { if (!enabled) expanded = false }

  DisposableEffect(Unit) {
    var active = true
    var tts: android.speech.tts.TextToSpeech? = null
    try {
      tts = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
        if (active) {
          val found = runCatching {
            if (status != android.speech.tts.TextToSpeech.SUCCESS) error("TTS initialization unavailable")
            tts?.voices?.filter { !it.isNetworkConnectionRequired }?.sortedWith(TtsVoiceLabels.sort)
                ?.map { InstalledVoiceChoice(it.name, TtsVoiceLabels.label(it), it.locale.getDisplayName(java.util.Locale.US)) }
                ?.distinctBy { it.name } ?: emptyList()
          }
          voices = found.getOrDefault(emptyList())
          loading = false
          loadFailed = found.isFailure
        }
      }
    } catch (_: Exception) {
      loading = false
      loadFailed = true
    }
    onDispose { active = false; runCatching { tts?.shutdown() } }
  }

  val label = installedVoiceLabel(current, voices, loading)
  Row(
      modifier = Modifier.fillMaxWidth().tvFocusableRow(enabled = enabled) { expanded = true }
          .semantics { contentDescription = "Spoken-time voice" }
          .padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Voice", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
    Text(label, color = IosSecondary, fontSize = Sizes.rowValue, fontWeight = FontWeight.SemiBold)
    Text("  ▾", color = IosSecondary, fontSize = Sizes.rowValue)
  }
  if (expanded && enabled) InstalledVoiceChoiceDialog(voices, current, loading, loadFailed, enabled,
      onDismiss = { expanded = false }, onPick = { if (enabled) onPick(it) })
}
