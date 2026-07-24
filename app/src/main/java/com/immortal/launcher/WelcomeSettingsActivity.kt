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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import org.json.JSONObject

private const val SHERPA_VOICE_PARAM = "sherpa_voice_name"

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
  val context = LocalContext.current
  var settings by remember { mutableStateOf(WelcomeConfig.load(context)) }

  val activity = context as? Activity
  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  // Android TTS voice audition. A single engine is kept alive for the screen's life to
  // both enumerate installed voices and play test phrases. (Piper neural voices were
  // dropped here — their model download is unreliable on the Portal. See project notes.)
  // voices: name -> friendly label; first entry is the engine default.
  var androidVoices by remember { mutableStateOf<List<Pair<String, String>>>(listOf("" to "Default voice")) }
  val ttsEngine = remember { mutableStateOf<android.speech.tts.TextToSpeech?>(null) }
  DisposableEffect(Unit) {
    var engine: android.speech.tts.TextToSpeech? = null
    engine = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
      if (status == android.speech.tts.TextToSpeech.SUCCESS) {
        val found = runCatching {
          engine?.voices
              ?.filter { !it.isNetworkConnectionRequired }
              ?.sortedWith(ttsVoiceSort())
              ?.map { it.name to androidVoiceLabel(it) }
              ?.distinctBy { it.first }
              ?: emptyList()
        }.getOrDefault(emptyList())
        if (found.isNotEmpty()) androidVoices = listOf("" to "Default voice") + found
      }
    }
    ttsEngine.value = engine
    onDispose { runCatching { engine?.stop() }; runCatching { engine?.shutdown() } }
  }
  fun testVoice(voiceName: String) {
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

  SettingsScaffold(
      title = "Welcome Overlay",
      subtitle = "Customize the greeting shown when the screensaver wakes up.",
      onBack = { activity?.finish() },
  ) {
      Column(modifier = Modifier.focusRequester(firstFocus).focusGroup()) {
        Card {
          ToggleRow("Show greeting message", settings.showGreeting) {
            SettingsDomains.welcome.apply(context, JSONObject().put("showGreeting", it))
            settings = WelcomeConfig.load(context)
          }
          Divider()
          ToggleRow("Show clock", settings.showClock) {
            SettingsDomains.welcome.apply(context, JSONObject().put("showClock", it))
            settings = WelcomeConfig.load(context)
          }
          Divider()
          ToggleRow("Show date", settings.showDate) {
            SettingsDomains.welcome.apply(context, JSONObject().put("showDate", it))
            settings = WelcomeConfig.load(context)
          }
          Divider()
          ToggleRow("Speak greeting (TTS)", settings.enableTts) {
            SettingsDomains.welcome.apply(context, JSONObject().put("enableTts", it))
            settings = WelcomeConfig.load(context)
          }
        }

        Spacer(Modifier.size(26.dp))

        SectionLabel("VOICE")
        Card {
          androidVoices.forEachIndexed { i, (name, label) ->
            if (i > 0) Divider()
            AndroidVoiceRow(
                label = label,
                selected = settings.ttsVoice == name,
                onSelect = {
                  WelcomeConfig.setTtsVoice(context, name)
                  settings = settings.copy(ttsVoice = name)
                },
                onTest = { testVoice(name) },
            )
          }
        }
        FooterNote(
            "Voice for the spoken greeting, from the voices installed on this device. " +
                "Tap Test to hear it. (Enable \"Speak greeting\" above for it to play on wake.)",
        )

        Spacer(Modifier.size(26.dp))

        SectionLabel("GREETINGS")
        Card {
          EditableTextRow("Your name", settings.userName) { text ->
            WelcomeConfig.setUserName(context, text)
            settings = settings.copy(userName = text)
          }
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

        Spacer(Modifier.size(26.dp))

        SectionLabel("TIMING")
        Card {
          DurationStepper(settings.durationMs / 1000) { sec ->
            val ms = WelcomeConfig.clampDuration(sec * 1000)
            SettingsDomains.welcome.apply(context, JSONObject().put("durationMs", ms))
            settings = WelcomeConfig.load(context)
          }
        }
        FooterNote(
            "How long the welcome overlay displays before auto-dismissing.",
        )

        Spacer(Modifier.size(26.dp))

        SectionLabel("TEXT SIZES")
        Card {
          SizeStepper("Greeting", settings.greetingSize) { size ->
            val clamped = WelcomeConfig.clampTextSize(size)
            WelcomeConfig.setGreetingSize(context, clamped)
            settings = settings.copy(greetingSize = clamped)
          }
          Divider()
          SizeStepper("Clock", settings.clockSize) { size ->
            val clamped = WelcomeConfig.clampTextSize(size)
            WelcomeConfig.setClockSize(context, clamped)
            settings = settings.copy(clockSize = clamped)
          }
          Divider()
          SizeStepper("Date", settings.dateSize) { size ->
            val clamped = WelcomeConfig.clampTextSize(size)
            WelcomeConfig.setDateSize(context, clamped)
            settings = settings.copy(dateSize = clamped)
          }
        }

        Spacer(Modifier.size(26.dp))

        SectionLabel("BACKGROUND")
        Card {
          OpacityStepper(settings.backgroundOpacity) { opacity ->
            val clamped = WelcomeConfig.clampOpacity(opacity)
            WelcomeConfig.setBackgroundOpacity(context, clamped)
            settings = settings.copy(backgroundOpacity = clamped)
          }
        }
        FooterNote(
            "Background darkness behind the welcome text (0% = transparent, 100% = opaque).",
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
              color = Color.White,
              fontSize = 18.sp,
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
}

private fun ttsVoiceSort(): Comparator<android.speech.tts.Voice> =
    compareBy<android.speech.tts.Voice> { ttsVoiceGroup(it) }
        .thenByDescending { it.quality }
        .thenBy { it.locale.displayLanguage }
        .thenBy { it.name }

private fun ttsVoiceGroup(v: android.speech.tts.Voice): Int {
  val n = v.name.lowercase()
  return when {
    n.startsWith("sherpa-kokoro-") -> 0
    n.startsWith("sherpa-piper-ro_ro-") -> 1
    n.startsWith("sherpa-piper-") -> 2
    else -> 3
  }
}

private fun ttsLocaleLabel(v: android.speech.tts.Voice): String {
  val language = v.locale.getDisplayLanguage(java.util.Locale.US).ifBlank { v.locale.language }
  val country = v.locale.country
  return if (country.isBlank()) language else "$language (${country.uppercase()})"
}

private fun ttsQualityLabel(v: android.speech.tts.Voice): String? =
    when {
      v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "HQ+"
      v.quality >= android.speech.tts.Voice.QUALITY_HIGH -> "HQ"
      else -> null
    }

@Composable
private fun AndroidVoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit,
) {
  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusableRow { onSelect() }
              .padding(horizontal = 18.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null)
    Text(label, color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f).padding(start = 12.dp))
    Surface(
        color = IosBlue,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.tvFocusable(RoundedCornerShape(10.dp), focusScale = 1f) { onTest() },
    ) {
      Text(
          "Test",
          color = Color.White,
          fontSize = 15.sp,
          textAlign = TextAlign.Center,
          modifier = Modifier.widthIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 9.dp),
      )
    }
  }
}

/** Turn an Android engine voice name like "en-us-x-sfg#female_1-local" into a label. */
private fun androidVoiceLabel(v: android.speech.tts.Voice): String {
  val n = v.name.lowercase()
  if (n.startsWith("sherpa-kokoro-")) {
    val voice = v.name.removePrefix("sherpa-kokoro-")
    return listOfNotNull("Kokoro $voice", ttsLocaleLabel(v), ttsQualityLabel(v)).joinToString(" - ")
  }
  if (n == "sherpa-piper-ro_ro-mihai-medium") {
    return listOfNotNull("Romanian Mihai", ttsLocaleLabel(v), ttsQualityLabel(v)).joinToString(" - ")
  }
  if (n.startsWith("sherpa-piper-")) {
    val voice = v.name.removePrefix("sherpa-piper-").replace('-', ' ')
    return listOfNotNull("Piper $voice", ttsLocaleLabel(v), ttsQualityLabel(v)).joinToString(" - ")
  }
  val gender = when {
    n.contains("female") -> "Female"
    n.contains("male") -> "Male"
    else -> null
  }
  val num = Regex("(\\d+)").find(n)?.value
  val base = listOfNotNull(gender, num?.let { "voice $it" }).joinToString(" ").ifBlank { v.name }
  val region = v.locale.country.ifBlank { v.locale.language }.uppercase()
  // Quality tier hint so the user can pick the better-sounding voices.
  val q = when {
    v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "HQ+"
    v.quality >= android.speech.tts.Voice.QUALITY_HIGH -> "HQ"
    else -> null
  }
  return listOfNotNull(base.takeIf { it.isNotBlank() }, region.takeIf { it.isNotBlank() }, q)
      .joinToString(" · ")
}

@Composable
private fun EditableTextRow(label: String, value: String, onChange: (String) -> Unit) {
  var editing by remember { mutableStateOf(false) }
  var tempValue by remember(value) { mutableStateOf(value) }

  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusableRow {
                if (!editing) {
                  editing = true
                  tempValue = value
                }
              }
              .padding(horizontal = 18.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
    if (editing) {
      OutlinedTextField(
          value = tempValue,
          onValueChange = { tempValue = it },
          singleLine = true,
          modifier = Modifier.width(200.dp),
      )
      Spacer(Modifier.width(8.dp))
      TextButton(onClick = {
        onChange(tempValue)
        editing = false
      }) {
        Text("Save", color = IosBlue)
      }
    } else {
      Text(
          value,
          color = IosSecondary,
          fontSize = Sizes.rowValue,
          modifier = Modifier.padding(start = 8.dp),
      )
    }
  }
}

@Composable
private fun DurationStepper(seconds: Int, onChange: (Int) -> Unit) {
  val src = remember { MutableInteractionSource() }
  val focused by src.collectIsFocusedAsState()
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                  when (e.key) {
                    Key.DirectionLeft -> { onChange(seconds - 1); true }
                    Key.DirectionRight -> { onChange(seconds + 1); true }
                    else -> false
                  }
                } else false
              }
              .focusable(interactionSource = src)
              .background(if (focused) Color(0xFFE5F0FF) else Color.Transparent)
              .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Display duration", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
    ArrowButton("◀", focused) { onChange(seconds - 1) }
    Text(
        "${seconds}s",
        color = if (focused) IosLabel else IosSecondary,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 52.dp),
    )
    ArrowButton("▶", focused) { onChange(seconds + 1) }
  }
}

@Composable
private fun SizeStepper(label: String, size: Float, onChange: (Float) -> Unit) {
  val src = remember { MutableInteractionSource() }
  val focused by src.collectIsFocusedAsState()
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                  when (e.key) {
                    Key.DirectionLeft -> { onChange(size - 2); true }
                    Key.DirectionRight -> { onChange(size + 2); true }
                    else -> false
                  }
                } else false
              }
              .focusable(interactionSource = src)
              .background(if (focused) Color(0xFFE5F0FF) else Color.Transparent)
              .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
    ArrowButton("◀", focused) { onChange(size - 2) }
    Text(
        "${size.toInt()}sp",
        color = if (focused) IosLabel else IosSecondary,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 64.dp),
    )
    ArrowButton("▶", focused) { onChange(size + 2) }
  }
}

@Composable
private fun OpacityStepper(opacity: Float, onChange: (Float) -> Unit) {
  val src = remember { MutableInteractionSource() }
  val focused by src.collectIsFocusedAsState()
  val percent = (opacity * 100).toInt()
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                  when (e.key) {
                    Key.DirectionLeft -> { onChange(opacity - 0.05f); true }
                    Key.DirectionRight -> { onChange(opacity + 0.05f); true }
                    else -> false
                  }
                } else false
              }
              .focusable(interactionSource = src)
              .background(if (focused) Color(0xFFE5F0FF) else Color.Transparent)
              .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Opacity", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
    ArrowButton("◀", focused) { onChange(opacity - 0.05f) }
    Text(
        "$percent%",
        color = if (focused) IosLabel else IosSecondary,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 64.dp),
    )
    ArrowButton("▶", focused) { onChange(opacity + 0.05f) }
  }
}
