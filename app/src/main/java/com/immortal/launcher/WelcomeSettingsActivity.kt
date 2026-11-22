/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.components.OnResumeAfterFirstComposition
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import org.json.JSONObject
import kotlin.math.roundToInt

private const val SHERPA_VOICE_PARAM = "sherpa_voice_name"
private const val SPEAK_GREETING_HELP = "Reads the greeting aloud."
private const val PERCENT = 100f
private const val OPACITY_STEP_PERCENT = 5

/**
 * Settings screen for the welcome-back overlay. Customize greeting messages,
 * colors, sizes, duration, and visibility of individual elements.
 */
class WelcomeSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { WelcomeSettingsScreen() } }
  }
}

@Composable
private fun WelcomeSettingsScreen() {
  val activity = LocalContext.current as? Activity
  SettingsScaffold(
      title = "Welcome Overlay",
      subtitle = "Customize the greeting shown when the screensaver wakes up.",
      onBack = { activity?.finish() },
  ) { WelcomeSettingsContent() }
}

/** Inlinable body of the Welcome settings screen — rendered both by [WelcomeSettingsScreen]
 *  (standalone Activity host) and by the two-panel settings detail panel. */
@Composable
internal fun WelcomeSettingsContent() {
  val context = LocalContext.current
  val advanced = com.immortal.launcher.ui.theme.LocalAdvancedSettings.current
  val pageActive by rememberUpdatedState(LocalApprovedLauncherPageActive.current)
  var settings by remember { mutableStateOf(WelcomeConfig.load(context)) }
  OnResumeAfterFirstComposition { settings = WelcomeConfig.load(context) }

  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  // Android TTS voice audition. A single engine is kept alive for the screen's life to
  // both enumerate installed voices and play test phrases. (Piper neural voices were
  // dropped here — their model download is unreliable on the Portal. See project notes.)
  var androidVoices by remember { mutableStateOf<List<InstalledVoiceChoice>>(emptyList()) }
  var voiceLoading by remember { mutableStateOf(true) }
  var voiceLoadFailed by remember { mutableStateOf(false) }
  var voiceChooserOpen by remember { mutableStateOf(false) }
  val ttsEngine = remember { mutableStateOf<android.speech.tts.TextToSpeech?>(null) }
  LaunchedEffect(advanced, settings.enableTts) {
    if (!advanced || !settings.enableTts) voiceChooserOpen = false
  }
  // The engine binds and enumerates on the main thread: start it after the page has settled.
  val scheduler = LocalSettingsComposition.current
  var engineAllowed by remember { mutableStateOf(false) }
  LaunchedEffect(scheduler) { scheduler.awaitPageSettled(); engineAllowed = true }
  if (engineAllowed && advanced && settings.enableTts) DisposableEffect(Unit) {
    var active = true
    var engine: android.speech.tts.TextToSpeech? = null
    androidVoices = emptyList()
    voiceLoading = true
    voiceLoadFailed = false
    try {
      engine = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
        if (active) {
          val found = runCatching {
            if (status != android.speech.tts.TextToSpeech.SUCCESS) error("TTS initialization unavailable")
            engine?.voices?.filter { !it.isNetworkConnectionRequired }?.sortedWith(TtsVoiceLabels.sort)
                ?.map { InstalledVoiceChoice(it.name, TtsVoiceLabels.label(it),
                    it.locale.getDisplayName(java.util.Locale.US)) }
                ?.distinctBy { it.name } ?: emptyList()
          }
          androidVoices = found.getOrDefault(emptyList())
          voiceLoading = false
          voiceLoadFailed = found.isFailure
        }
      }
      ttsEngine.value = engine
    } catch (_: Exception) {
      voiceLoading = false
      voiceLoadFailed = true
    }
    onDispose {
      active = false
      ttsEngine.value = null
      runCatching { engine?.stop() }
      runCatching { engine?.shutdown() }
    }
  }
  fun testVoice(voiceName: String) {
    if (!advanced || !settings.enableTts || voiceLoading || voiceLoadFailed) return
    val t = ttsEngine.value ?: return
    val selectedVoice =
        if (voiceName.isNotBlank()) t.voices?.firstOrNull { it.name == voiceName } else null
    val sample =
        if (selectedVoice != null) {
          runCatching { t.language = selectedVoice.locale }
          runCatching { t.voice = selectedVoice }
          if (selectedVoice.locale.language == "ro") {
            "Salut, aceasta este vocea romaneasca."
          } else {
            "Hi, this is your welcome voice. Welcome home."
          }
        } else {
          t.language = java.util.Locale.getDefault()
          "Hi, this is your welcome voice. Welcome home."
        }
    val params =
        android.os.Bundle().apply {
          if (voiceName.isNotBlank()) putString(SHERPA_VOICE_PARAM, voiceName)
        }
    t.speak(sample,
        android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, "welcome_test")
  }

      Column(modifier = Modifier.focusRequester(firstFocus).focusGroup()) {
        SectionLabel("Show", leading = true)
        Card {
          ToggleRow("Greeting message", settings.showGreeting) {
            SettingsDomains.welcome.apply(context, JSONObject().put("showGreeting", it))
            settings = WelcomeConfig.load(context)
          }
          Divider()
          ToggleRow("Clock", settings.showClock) {
            SettingsDomains.welcome.apply(context, JSONObject().put("showClock", it))
            settings = WelcomeConfig.load(context)
          }
          Divider()
          ToggleRow("Date", settings.showDate) {
            SettingsDomains.welcome.apply(context, JSONObject().put("showDate", it))
            settings = WelcomeConfig.load(context)
          }
          Divider()
          CompositionLocalProvider(LocalApprovedSettingsHelp provides SPEAK_GREETING_HELP) {
            ToggleRow("Speak greeting", settings.enableTts) {
              SettingsDomains.welcome.apply(context, JSONObject().put("enableTts", it))
              settings = WelcomeConfig.load(context)
            }
          }
        }


        if (advanced && settings.enableTts) {
        SectionLabel("Voice")
        Card {
          Row(Modifier.fillMaxWidth().tvFocusableRow { voiceChooserOpen = true }
              .padding(horizontal = 18.dp, vertical = 16.dp),
              verticalAlignment = Alignment.CenterVertically) {
            Text("Greeting voice", color = IosLabel, fontSize = Sizes.rowTitle,
                modifier = Modifier.weight(1f))
            Text(installedVoiceLabel(settings.ttsVoice, androidVoices, voiceLoading),
                color = IosSecondary, fontSize = Sizes.rowValue, fontWeight = FontWeight.SemiBold)
            Text("  ▾", color = IosSecondary, fontSize = Sizes.rowValue)
          }
        }
        FooterNote("Choose an offline voice for the spoken greeting. Test a voice before choosing; " +
            "testing doesn't change your saved voice.")
        if (voiceChooserOpen) InstalledVoiceChoiceDialog(
            androidVoices, settings.ttsVoice, voiceLoading, voiceLoadFailed, enabled = true,
            onDismiss = { voiceChooserOpen = false },
            onPick = { name ->
              if (advanced && settings.enableTts) {
                WelcomeConfig.setTtsVoice(context, name)
                settings = settings.copy(ttsVoice = name)
              }
            },
            help = "Choose an offline voice for the welcome greeting. This doesn't change the spoken-time voice.",
            onPreview = ::testVoice,
            previewEnabled = !voiceLoading && !voiceLoadFailed && ttsEngine.value != null,
        )

        }

        SectionLabel("Greeting")
        Card {
          EditableTextRow("Your name", settings.userName) { text ->
            WelcomeConfig.setUserName(context, text)
            settings = settings.copy(userName = text)
          }
          if (advanced) {
          Divider()
          EditableTextRow("Night (22:00-04:59)", settings.greetingNight) { text ->
            WelcomeConfig.setGreetingNight(context, text)
            settings = settings.copy(greetingNight = text)
          }
          Divider()
          EditableTextRow("Morning (05:00-11:59)", settings.greetingMorning) { text ->
            WelcomeConfig.setGreetingMorning(context, text)
            settings = settings.copy(greetingMorning = text)
          }
          Divider()
          EditableTextRow("Afternoon (12:00-16:59)", settings.greetingAfternoon) { text ->
            WelcomeConfig.setGreetingAfternoon(context, text)
            settings = settings.copy(greetingAfternoon = text)
          }
          Divider()
          EditableTextRow("Evening (17:00-21:59)", settings.greetingEvening) { text ->
            WelcomeConfig.setGreetingEvening(context, text)
            settings = settings.copy(greetingEvening = text)
          }
          }
        }


        // Timing & look share one card, as in the approved mock; duration and opacity stay ADV.
        SectionLabel("Timing & look")
        Card {
          if (advanced) {
            val duration = remember { WelcomeDurationAdapter() }
            fun adjustDuration(increase: Boolean) {
              if (pageActive) settings = duration.adjust(settings, increase,
                  apply = { key, ms ->
                    SettingsDomains.welcome.apply(context, JSONObject().put(key, ms))
                  },
                  reload = { SettingsDomains.welcome.load(context) })
            }
            Stepper(duration.title, duration.label(settings), enabled = pageActive,
                onMinus = { adjustDuration(false) }, onPlus = { adjustDuration(true) })
            Divider()
          }
          val scales = listOf(WelcomeConfig.SCALE_SMALL to "Small", WelcomeConfig.SCALE_MEDIUM to "Medium",
              WelcomeConfig.SCALE_LARGE to "Large")
          SettingsChoiceTilesRow("Text size", null,
              scales.map { (scale, label) -> SettingsTile(label, scale == settings.textScale) }) { index ->
            val scale = scales[index].first
            if (scale != settings.textScale) {
              WelcomeConfig.setTextScale(context, scale)
              settings = settings.copy(textScale = scale)
            }
          }
          if (advanced) {
            Divider()
            SettingsSliderRow("Background opacity", null, (settings.backgroundOpacity * PERCENT).roundToInt(),
                0, PERCENT.toInt(), OPACITY_STEP_PERCENT, { "$it%" }) { percent ->
              val clamped = WelcomeConfig.clampOpacity(percent / PERCENT)
              WelcomeConfig.setBackgroundOpacity(context, clamped)
              settings = settings.copy(backgroundOpacity = clamped)
            }
          }
        }
        if (advanced) FooterNote(
            "Display duration is how long the welcome overlay stays up. Background opacity darkens " +
                "the photo behind the text (0% = transparent, 100% = opaque).",
        )

        Spacer(Modifier.size(28.dp))
        Surface(
            color = IosBlue,
            shape = RoundedCornerShape(16.dp),
            modifier =
                Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                  val intent = Intent(context, PhotoFramePreviewActivity::class.java)
                  intent.putExtra(PhotoFramePreviewActivity.EXTRA_SHOW_WELCOME, true)
                  context.startActivity(intent)
                },
        ) {
          Text(
              "Preview welcome overlay",
              color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
              fontSize = 24.sp,
              fontWeight = FontWeight.SemiBold,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
          )
        }

        Spacer(Modifier.size(16.dp))

        FooterNote(
            "Tip: The welcome overlay appears when the screensaver starts from sleep. " +
                "Tap anywhere on the overlay to dismiss it early, or wait for it to auto-fade.",
        )
      }
}


@Composable
private fun EditableTextRow(label: String, value: String, onChange: (String) -> Unit) {
  val pageActive = LocalApprovedLauncherPageActive.current
  var editing by remember { mutableStateOf(false) }
  var draft by remember { mutableStateOf(value) }
  LaunchedEffect(pageActive) {
    if (!pageActive) {
      editing = false
      draft = value
    }
  }
  fun dismiss() { editing = false; draft = value }
  NavRow(label, value = value.ifBlank { "Not set" }) {
    if (pageActive) {
      draft = value
      editing = true
    }
  }
  if (editing && pageActive) ApprovedSettingsDialog(label, onDismiss = ::dismiss,
      help = if (label == "Your name") "Shown after the greeting. Leave blank to omit your name."
          else "The greeting shown at this time of day. Changes apply when you choose Save.") {
    IosTextField(draft, { draft = it }, label, modifier = Modifier.semantics {
      contentDescription = "$label text"
    })
    Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      ActionPill("Save") {
        if (editing && pageActive) {
          onChange(draft)
          dismiss()
        }
      }
      ApprovedSettingsTextButton("Cancel", ::dismiss)
    }
  }
}


