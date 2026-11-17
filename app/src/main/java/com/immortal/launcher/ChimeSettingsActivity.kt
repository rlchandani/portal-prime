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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChimeSettingsContent() {
  val context = LocalContext.current
  val application = context.applicationContext
  val writes = remember(application) { ChimeSettingsWrites.queue(application) }
  var settings by remember(application) { mutableStateOf(ChimeConfig.load(application)) }
  // Values were just read; rows start enabled unless an earlier visit's write is still in flight.
  var saveState by remember { mutableStateOf(openedSaveState(writes.isIdle)) }
  val openedIdle = remember { !saveState.saving }
  val applying = saveState.saving
  val failed = saveState.failed
  val completion = rememberCoroutineScope()
  val lifecycleOwner = LocalLifecycleOwner.current
  val pageActive = LocalApprovedLauncherPageActive.current
  var previewState by remember { mutableStateOf(SoundPreviewState()) }
  val previews = remember(application) { SoundPreviewSession { previewState = it } }
  DisposableEffect(lifecycleOwner, pageActive, previews) {
    fun refresh() = previews.setActive(pageActive &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    val observer = LifecycleEventObserver { _, _ -> refresh() }
    lifecycleOwner.lifecycle.addObserver(observer)
    refresh()
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer); previews.setActive(false) }
  }
  DisposableEffect(previews) { onDispose { previews.dispose() } }
  LaunchedEffect(settings.volume) { if (settings.volume == 0) previews.stop() }
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
    var first = true
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      if (first && openedIdle) {
        // Opened with nothing in flight: confirm quietly. An unchanged read is equal and
        // recomposes nothing; rows stay enabled instead of flipping a frame after the switch.
        first = false
        val revision = saveState.revision
        val loaded = readSnapshot()
        if (revision == saveState.revision) settings = loaded
        return@repeatOnLifecycle
      }
      first = false
      saveState = saveState.beginRefresh()
      publishAfterSave(saveState.revision)
    }
  }
  fun audition(preview: ChimePreview) {
    if (!ChimePreviews.enabled(saveState.saving, settings.volume)) return
    val volume = (settings.volume / 100f).coerceIn(0f, 1f)
    previews.play(preview.id) { playing, finished ->
      val resource = preview.resource
      if (resource == null) SoundPreviewPlayback.voice(application, settings.spokenVoice, volume, playing, finished)
      else SoundPreviewPlayback.tone(application, resource, volume, playing, finished)
    }
  }
  fun apply(key: String, value: Any) {
    if (saveState.saving) return
    saveState = saveState.beginEdit(writes.failureCount)
    val revision = saveState.revision
    writes.submit(key, value)
    completion.launch { publishAfterSave(revision) }
  }
  // One identity for the page's life, so rows that did not change skip on recomposition.
  val latestApply = rememberUpdatedState<(String, Any) -> Unit>(::apply)
  val onApply = remember { { key: String, value: Any -> latestApply.value(key, value) } }

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
          settings.quietHoursOn && settings.quietStartMin == settings.quietEndMin ->
            "Quiet times match, so scheduled sounds aren’t silenced. Choose different times."
          else -> "Scheduled sounds respect your quiet hours."
        })
      }

      // Rows go straight into the page's list (no nested list), each precomposed on its own.
      Column {
        SettingsRows(SettingsDomains.chime, settings, exclude = setOf("spokenVoice"),
          editsEnabled = !applying, deferRows = true, onApply = onApply)
      }

      // Bespoke: voice picker + test buttons the registry can't model (TTS voice enumeration is
      // on-device only; test buttons are actions, not settings).
      SectionLabel("Preview sounds")
      val previewEnabled = ChimePreviews.enabled(applying, settings.volume)
      Card {
        Text(ChimePreviews.message(previewState),
            color = if (previewState.phase == SoundPreviewPhase.FAILED) IosRed else IosSecondary,
            fontSize = Sizes.rowSub, modifier = Modifier.padding(18.dp)
                .semantics { contentDescription = "Sound preview: ${previewState.phase.name.lowercase()}" })
        if (settings.spokenTimeOn) {
          ChimeVoicePicker(settings.spokenVoice, enabled = !applying) { name ->
            // Installed identities share the same save lock, worker and readback as other edits.
            apply("spokenVoice", name)
          }
        }
        FlowRow(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          ChimePreviews.available(settings).forEach { preview ->
            ChimePreviewButton(preview.label, enabled = previewEnabled) { audition(preview) }
          }
          if (ChimePreviews.isPlaying(previewState)) {
            ChimePreviewButton("Stop preview", enabled = true, isStop = true) { previews.stop() }
          }
        }
      }
      FooterNote(ChimePreviews.footer(settings.volume))
    }
}
