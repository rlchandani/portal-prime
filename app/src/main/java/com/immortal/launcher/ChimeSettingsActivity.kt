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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import org.json.JSONObject

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
  var settings by remember { mutableStateOf(ChimeConfig.load(context)) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      // Scalar controls (toggles, volumes, quiet times, sunrise sound) render from the `chime`
      // registry domain - the same specs the phone remote uses. Apply routes through the domain so
      // its onApplied (ChimeScheduler.reschedule) fires here too, not just from the remote.
      SettingsList(SettingsDomains.chime, settings) { k, v ->
        SettingsDomains.chime.apply(context, JSONObject().put(k, v))
        settings = ChimeConfig.load(context)
      }

      // Bespoke: voice picker + test buttons the registry can't model (TTS voice enumeration is
      // on-device only; test buttons are actions, not settings).
      if (settings.spokenTimeOn || settings.hourlyChimeOn || settings.goldenHourOn) {
        Card {
          if (settings.spokenTimeOn) {
            VoicePicker(settings.spokenVoice) { name ->
              ChimeConfig.setSpokenVoice(context, name)
              settings = settings.copy(spokenVoice = name)
            }
            TestButton("Test voice") { ChimePlayer.testVoice(context, settings.spokenVoice) }
          }
          if (settings.hourlyChimeOn) {
            TestButton("Play chime") { ChimePlayer.playChime(context) }
          }
          if (settings.goldenHourOn) {
            TestButton("Test sunrise") { ChimePlayer.playSunriseTone(context) }
            TestButton("Test sunset") { ChimePlayer.playSunsetTone(context) }
          }
          TestButton("Play ping") { ChimePlayer.playPing(context, repeats = 1) }
        }
      }
    }
}

@Composable
private fun TestButton(label: String, onClick: () -> Unit) {
  Surface(
      color = IosFocusTint,
      shape = RoundedCornerShape(12.dp),
      modifier =
          Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
              .tvFocusable(RoundedCornerShape(12.dp), focusScale = 1f) { onClick() },
  ) {
    Text(
        "▶  $label",
        color = IosBlue,
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
    )
  }
}

/** Dropdown of the TTS engine's installed voices for the chosen locale. The empty
 *  name means "engine default". Voices are enumerated from a throwaway TextToSpeech. */
@Composable
private fun VoicePicker(current: String, onPick: (String) -> Unit) {
  val context = LocalContext.current
  // name -> friendly label; first entry is always the engine default.
  var voices by remember { mutableStateOf<List<Pair<String, String>>>(listOf("" to "Default voice")) }
  var expanded by remember { mutableStateOf(false) }

  DisposableEffect(Unit) {
    var tts: android.speech.tts.TextToSpeech? = null
    tts = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
      if (status == android.speech.tts.TextToSpeech.SUCCESS) {
        val found = runCatching {
          tts?.voices
              ?.filter { !it.isNetworkConnectionRequired }
              ?.sortedWith(TtsVoiceLabels.sort)
              ?.map { it.name to TtsVoiceLabels.label(it) }
              ?.distinctBy { it.first }
              ?: emptyList()
        }.getOrDefault(emptyList())
        if (found.isNotEmpty()) voices = listOf("" to "Default voice") + found
      }
    }
    onDispose { runCatching { tts?.shutdown() } }
  }

  val label = voices.firstOrNull { it.first == current }?.second ?: "Default voice"
  Box {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .tvFocusableRow { expanded = true }
                .padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Voice", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
      Text(label, color = IosSecondary, fontSize = Sizes.rowValue, fontWeight = FontWeight.SemiBold)
      Text("  ▾", color = IosSecondary, fontSize = Sizes.rowValue)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      voices.forEach { (name, friendly) ->
        DropdownMenuItem(
            text = { Text(friendly) },
            onClick = { onPick(name); expanded = false })
      }
    }
  }
}


