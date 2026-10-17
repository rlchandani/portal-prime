/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared utility content for Home modals and Tools detail workspaces. Home retains its bounded
 * card, title and Close control; [ApprovedToolWorkspace] supplies the page chrome in Tools.
 * Computation and persistence stay in the matching feature objects.
 */

/* ------------------------------------------------------------------ ISS passes */

@Composable
internal fun IssOverlay(onDismiss: () -> Unit) {
  val context = LocalContext.current
  var passes by remember { mutableStateOf<List<IssPasses.Pass>?>(null) }
  var foregroundAt by remember { mutableStateOf(System.currentTimeMillis()) }
  var loading by remember { mutableStateOf(true) }
  var message by remember { mutableStateOf<String?>(null) }
  var retry by remember { mutableStateOf(0) }
  LaunchedEffect(retry) {
    loading = true
    message = null
    foregroundAt = System.currentTimeMillis()
    passes = passes?.filter { it.endMillis > foregroundAt }
    try {
      val result = withContext(Dispatchers.IO) { IssPasses.predictStatus(context) }
      foregroundAt = System.currentTimeMillis()
      val predicted = result.passes.filter { it.endMillis > foregroundAt }
      passes = passes?.filter { it.endMillis > foregroundAt }
      if (result.problem == null) passes = predicted
      message = issPredictionMessage(IssPasses.Prediction(predicted, result.problem))
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      foregroundAt = System.currentTimeMillis()
      passes = passes?.filter { it.endMillis > foregroundAt }
      message = issPredictionMessage(IssPasses.Prediction(
          emptyList(), IssPasses.PredictionProblem.CALCULATION))
    } finally { loading = false }
  }

  BackHandler { onDismiss() }
  Scrim(onDismiss) {
    ToolCard(onClose = onDismiss) {
      ToolTitle("ISS passes")
      ApprovedAppsButton("Refresh passes") { retry++ }
      val p = passes.orEmpty().filter { it.endMillis > foregroundAt }
      when {
        p.isEmpty() ->
            Text(
                if (loading) "Finding passes…" else message ?: issPredictionMessage(
                    IssPasses.Prediction(emptyList()))!!,
                color = IosSecondary,
                fontSize = 24.sp,
                lineHeight = 34.sp,
            )
        else -> {
          val first = p.first()
          Text(
              buildString {
                append(if (first.visible) "Visible pass " else "Passes over ")
                append(IssPasses.timeLabel(first.startMillis))
                append(if (first.visible) " ✨" else "")
              },
              color = if (first.visible) Color(0xFFFF9F0A) else IosLabel,
              fontSize = 22.sp,
              fontWeight = FontWeight.SemiBold,
          )
          Column(
              modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            p.forEach { pass ->
              Surface(color = IosBackground, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(IssPasses.timeLabel(pass.startMillis), color = IosLabel, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (pass.visible) {
                      Surface(color = Color(0x1AFF9F0A), shape = RoundedCornerShape(8.dp)) {
                        Text("✨ visible", color = Color(0xFFFF9F0A), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                      }
                    }
                  }
                  Text(
                      "Rises ${pass.startDir} · peak ${pass.maxElevationDeg}° to the ${pass.peakDir} · sets ${pass.endDir}",
                      color = IosSecondary,
                      fontSize = 22.sp,
                      modifier = Modifier.padding(top = 4.dp),
                  )
                }
              }
            }
          }
          Text(
              "Times are local. ✨ means it should be bright enough to see — go outside and look up to the listed direction.",
              color = IosSecondary,
              fontSize = 21.sp,
              lineHeight = 30.sp,
          )
        }
      }
      if (p.isNotEmpty() && message != null) {
        Text(message!!, color = IosSecondary, fontSize = 24.sp, lineHeight = 34.sp)
      }
    }
  }
}

/* ------------------------------------------------------------------ Aurora */

@Composable
internal fun AuroraOverlay(onDismiss: () -> Unit) {
  val context = LocalContext.current
  var status by remember { mutableStateOf<Aurora.Status?>(null) }
  var loaded by remember { mutableStateOf(false) }
  var retry by remember { mutableStateOf(0) }
  LaunchedEffect(retry) {
    loaded = false
    status = withContext(Dispatchers.IO) { Aurora.status(context) }
    loaded = true
  }

  BackHandler { onDismiss() }
  Scrim(onDismiss) {
    ToolCard(onClose = onDismiss) {
      ToolTitle("Aurora outlook")
      ApprovedAppsButton("Refresh outlook") { retry++ }
      val st = status
      when {
        !loaded -> Text("Checking the K-index…", color = IosSecondary, fontSize = 24.sp)
        st == null ->
            Text(
                "Couldn't fetch the K-index. Check the device is online and that your location is " +
                    "set (it follows the weather tile).",
                color = IosSecondary,
                fontSize = 24.sp,
                lineHeight = 34.sp,
            )
        else -> {
          val accent =
              when (st.chance) {
                Aurora.Chance.LIKELY -> IosGreen
                Aurora.Chance.POSSIBLE -> Color(0xFFFF9F0A)
                Aurora.Chance.SLIM -> IosSecondary
                Aurora.Chance.NONE -> IosSecondary
              }
          Text(st.headline, color = accent, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
          Text(st.detail, color = IosSecondary, fontSize = 24.sp, lineHeight = 34.sp)
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            AuroraStat("Kp now", Aurora.fmtKp(st.kpNow), Modifier.weight(1f))
            AuroraStat("Next 24 h peak", Aurora.fmtKp(st.kpForecast), Modifier.weight(1f))
            AuroraStat("Look", st.lookToward, Modifier.weight(1f))
          }
          Text(
              "Source: NOAA SWPC planetary K-index. Best after dark, away from city lights, with a " +
                  "clear ${if (st.lookToward == "N") "northern" else "southern"} horizon.",
              color = IosSecondary,
              fontSize = 21.sp,
              lineHeight = 30.sp,
          )
        }
      }
    }
  }
}

@Composable
private fun AuroraStat(label: String, value: String, modifier: Modifier = Modifier) {
  Surface(color = IosBackground, shape = RoundedCornerShape(14.dp), modifier = modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth()) {
      Text(value, color = IosLabel, fontSize = 28.sp, fontWeight = FontWeight.Bold)
      Text(label, color = IosSecondary, fontSize = 21.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
    }
  }
}

/* ------------------------------------------------------------------ Speed test */

@Composable
internal fun SpeedTestOverlay(onDismiss: () -> Unit) {
  BackHandler { onDismiss() }
  if (LocalApprovedToolPage.current) ApprovedToolsSpeedScreen(onDismiss)
  else Scrim(onDismiss) {
    Surface(color = IosCard, shape = RoundedCornerShape(28.dp), border = androidx.compose.foundation.BorderStroke(1.dp, IosDivider),
        modifier = Modifier.widthIn(max = 1120.dp).heightIn(max = 1040.dp)) {
      ApprovedToolsSpeedScreen(onDismiss)
    }
  }
}

/* ------------------------------------------------------------------ Converter */

@Composable
internal fun ConverterOverlay(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val categories = remember { Converter.UNIT_CATEGORIES.map { it.first } + "Currency" }
  var category by remember { mutableStateOf(categories.first()) }
  val units =
      remember(category) {
        if (category == "Currency") Converter.CURRENCIES
        else Converter.UNIT_CATEGORIES.first { it.first == category }.second.keys.toList()
      }
  var from by remember(category) { mutableStateOf(units.first()) }
  var to by remember(category) { mutableStateOf(units.getOrElse(1) { units.first() }) }
  var input by remember { mutableStateOf("1") }
  var result by remember { mutableStateOf("") }

  LaunchedEffect(category, from, to, input) {
    val v = input.toDoubleOrNull()
    result =
        when {
          v == null -> ""
          category == "Currency" ->
              withContext(Dispatchers.IO) { Converter.convertCurrency(context, from, to, v) }
                  ?.let { trimNum(it) } ?: "no rate (offline?)"
          else -> trimNum(Converter.convert(category, from, to, v))
        }
  }

  var picker by remember { mutableStateOf<String?>(null) }
  BackHandler { if (picker != null) picker = null else onDismiss() }
  Scrim(onDismiss) {
    ToolCard(onClose = onDismiss) {
      ToolTitle("Converter")
      ToolGroup("Convert") {
        ToolValueRow("Category", category) { picker = "Category" }
        ToolValueRow("From", from) { picker = "From" }
        ToolValueRow("To", to) { picker = "To" }
        Row(Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Value", color = IosLabel, fontSize = 26.sp, modifier = Modifier.weight(1f))
          androidx.compose.material3.OutlinedTextField(value = input, onValueChange = { input = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
              textStyle = androidx.compose.ui.text.TextStyle(fontSize = 26.sp, color = IosLabel), shape = RoundedCornerShape(16.dp), colors = iosTextFieldColors(), modifier = Modifier.width(320.dp))
        }
      }
      ToolGroup("Result") {
        Text(if (input.toDoubleOrNull() == null) "Enter a valid number." else if (result.isBlank()) "Calculating…" else "$result $to", color = IosLabel, fontSize = 40.sp, lineHeight = 54.sp, modifier = Modifier.padding(28.dp))
      }
    }
  }
  picker?.let { which ->
    ToolDialog(which, { picker = null }) {
      val options = if (which == "Category") categories else units
      options.forEach { option -> ToolValueRow(option, if (option == when (which) { "Category" -> category; "From" -> from; else -> to }) "✓" else "") {
        when (which) { "Category" -> category = option; "From" -> from = option; else -> to = option }; picker = null
      } }
    }
  }
}


/* ------------------------------------------------------------------ Timers */

@Composable
internal fun TimersOverlay(onDismiss: () -> Unit) {
  val context = LocalContext.current
  var timers by remember { mutableStateOf(TimerConfig.load(context)) }
  var now by remember { mutableStateOf(System.currentTimeMillis()) }
  var custom by remember { mutableStateOf(false) }
  var hours by remember { mutableStateOf("0") }
  var minutes by remember { mutableStateOf("5") }
  var seconds by remember { mutableStateOf("0") }
  var cancelTimer by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(timers) {
    now = System.currentTimeMillis()
    while (timers.any { it.endAtMillis > now }) {
      delay(1000)
      now = System.currentTimeMillis()
    }
  }
  fun reload() {
    timers = TimerConfig.load(context)
  }

  BackHandler { onDismiss() }
  Scrim(onDismiss) {
    ToolCard(onClose = onDismiss) {
      ToolTitle("Timers")
      Text("Start a timer", color = IosSecondary, fontSize = 22.sp)
      val presets = listOf(1, 3, 5, 10, 15, 30)
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        presets.take(3).forEach { m ->
          PillButton("${m}m", { TimerConfig.add(context, "$m min", m * 60_000L); reload() }, Modifier.weight(1f))
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        presets.drop(3).forEach { m ->
          PillButton("${m}m", { TimerConfig.add(context, "$m min", m * 60_000L); reload() }, Modifier.weight(1f))
        }
      }
      ApprovedAppsButton("Custom timer") { custom = true }
      val active = timers.filter { it.endAtMillis > now }.sortedBy { it.endAtMillis }
      if (active.isEmpty()) Text("No timers running.", color = IosSecondary, fontSize = 24.sp)
      active.forEach { t ->
        Surface(color = IosBackground, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
          Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
          ) {
            Text(t.label, color = IosLabel, fontSize = 24.sp, modifier = Modifier.weight(1f))
            Text(fmtClock(t.endAtMillis - now), color = Color(0xFFFF9F0A), fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.size(16.dp))
            Surface(
                color = Color(0x1AFF3B30),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.tvFocusable(RoundedCornerShape(12.dp), focusScale = 1f) { cancelTimer = t.id },
            ) {
              Text("Cancel", color = IosRed, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
            }
          }
        }
      }
    }
  }
  if (custom) ToolDialog("Custom timer", { custom = false }) {
    listOf(Triple("Hours", hours, 0), Triple("Minutes", minutes, 1), Triple("Seconds", seconds, 2)).forEach { (label, value, index) ->
      Text(label, color = IosSecondary, fontSize = 22.sp)
      androidx.compose.material3.OutlinedTextField(value, { next -> if (next.all(Char::isDigit)) when (index) { 0 -> hours = next; 1 -> minutes = next; else -> seconds = next } },
          singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 26.sp, color = IosLabel), colors = iosTextFieldColors(), modifier = Modifier.fillMaxWidth())
    }
    val h = hours.toLongOrNull() ?: 0L
    val m = minutes.toLongOrNull() ?: 0L
    val sec = seconds.toLongOrNull() ?: 0L
    val valid = h in 0..23 && m in 0..59 && sec in 0..59 && (h + m + sec > 0)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      ApprovedAppsButton("Cancel") { custom = false }
      ApprovedAppsButton("Start timer", primary = true, enabled = valid) { TimerConfig.add(context, "Timer", (h * 3600 + m * 60 + sec) * 1000); reload(); custom = false }
    }
  }
  cancelTimer?.let { id -> ToolDialog("Cancel this timer?", { cancelTimer = null }) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      ApprovedAppsButton("Keep timer") { cancelTimer = null }
      ApprovedAppsButton("Cancel timer") { TimerConfig.remove(context, id); reload(); cancelTimer = null }
    }
  } }
}


/* ------------------------------------------------------------------ Leave a note */

@Composable
internal fun NotesOverlay(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val pageActive by rememberUpdatedState(LocalApprovedLauncherPageActive.current)
  var text by remember { mutableStateOf(NotesConfig.loadText(context)) }
  var saved by remember { mutableStateOf(false) }
  val audio = remember { AudioNote(context) }
  var recording by remember { mutableStateOf(false) }
  var hasAudio by remember { mutableStateOf(NotesConfig.hasAudioNote(context)) }
  var live by remember { mutableStateOf(true) }
  var playing by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }
  var removeMemo by remember { mutableStateOf(false) }
  var clearText by remember { mutableStateOf(false) }
  fun stopAudio() {
    if (audio.isRecording) hasAudio = audio.stopRecording()
    audio.stopPlaying()
    recording = false
    playing = false
  }
  DisposableEffect(owner, audio) {
    live = true
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP) stopAudio()
    }
    owner.lifecycle.addObserver(observer)
    if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) stopAudio()
    onDispose {
      live = false
      owner.lifecycle.removeObserver(observer)
      audio.release()
    }
  }

  fun isActive() = live && pageActive && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
  fun beginRecord() {
    if (!isActive()) return
    if (audio.startRecording()) { recording = true; playing = false } else message = "The microphone couldn’t start recording. Check app permissions and try again."
  }
  val micPerm =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (isActive()) {
          if (granted) beginRecord() else message = "Microphone access is needed to record a voice memo."
        }
      }
  fun record() {
    if (!isActive()) return
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED)
        beginRecord()
    else micPerm.launch(Manifest.permission.RECORD_AUDIO)
  }

  BackHandler { onDismiss() }
  Scrim(onDismiss) {
    ToolCard(onClose = onDismiss) {
      ToolTitle("Leave a note")
      message?.let { Text(it, color = IosSecondary, fontSize = 24.sp, lineHeight = 34.sp) }
      TextField(
          value = text,
          onValueChange = { text = it; saved = false },
          textStyle = androidx.compose.ui.text.TextStyle(fontSize = 24.sp),
          colors = iosFilledTextFieldColors(),
          modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
      )
      PillButton(
          if (saved) "Saved ✓" else "Save note",
          { NotesConfig.saveText(context, text); saved = true },
          Modifier.fillMaxWidth(),
      )
      ApprovedAppsButton("Clear note") { clearText = true }
      Text("Voice memo", color = IosSecondary, fontSize = 22.sp)
      if (recording) {
        PillButton(
            "■ Stop recording",
            { hasAudio = audio.stopRecording(); recording = false; if (!hasAudio) message = "The recording was too short. Please try again." },
            Modifier.fillMaxWidth(),
        )
      } else {
        PillButton(if (hasAudio) "Re-record" else "● Record", { record() }, Modifier.fillMaxWidth())
      }
      if (hasAudio && !recording) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          PillButton(if (playing) "Stop playback" else "Play memo", {
            if (playing) { audio.stopPlaying(); playing = false }
            else if (isActive()) { playing = true; audio.play { if (live) playing = false } }
          }, Modifier.weight(1f))
          PillButton("Delete memo", { removeMemo = true }, Modifier.weight(1f))
        }
      }
    }
  }
      // Saving is explicit; clearing or deleting always asks before modifying the saved note.
  if (removeMemo) ToolDialog("Delete voice memo?", { removeMemo = false }) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      ApprovedAppsButton("Cancel") { removeMemo = false }
      ApprovedAppsButton("Delete memo") { audio.stopPlaying(); playing = false; NotesConfig.clearAudio(context); hasAudio = false; removeMemo = false }
    }
  }
  if (clearText) ToolDialog("Clear this note?", { clearText = false }) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      ApprovedAppsButton("Cancel") { clearText = false }
      ApprovedAppsButton("Clear note") { NotesConfig.saveText(context, ""); text = ""; saved = true; clearText = false }
    }
  }
}

/* ------------------------------------------------------------------ small shared bits */

/** A horizontally-scrolling row of selectable chips (category / unit pickers). Sized big for the
 *  Portal's wide panel: 20sp labels with generous tap padding. */
@Composable
private fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    options.forEach { opt ->
      val on = opt == selected
      Surface(
          color = if (on) IosBlue else IosBackground,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) { onSelect(opt) },
      ) {
        Text(opt, color = if (on) androidx.compose.material3.MaterialTheme.colorScheme.onPrimary else IosLabel, fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
      }
    }
  }
}

/** A full-width-ish pill action button used by the timer / notes overlays. Big, touch-friendly. */
@Composable
private fun PillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Surface(
      color = IosBackground,
      shape = RoundedCornerShape(16.dp),
      modifier = modifier.tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) { onClick() },
  ) {
    Text(
        label,
        color = IosBlue,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp).fillMaxWidth(),
    )
  }
}

/** Trim a converted number to at most 4 decimals, dropping trailing zeros. */
private fun trimNum(v: Double): String = String.format("%.4f", v).trimEnd('0').trimEnd('.')

/** Remaining-time as `m:ss` (or `h:mm:ss`). */
private fun fmtClock(ms: Long): String {
  val s = ms.coerceAtLeast(0L) / 1000
  val h = s / 3600
  val m = (s % 3600) / 60
  val sec = s % 60
  return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
}

/* ------------------------------------------------------------------ shared scaffolding */

/**
 * Full-screen scrim over the home page. Tapping outside the card does NOT dismiss (the tap is
 * swallowed) — closing is only via the card's explicit Close button or Back, so an accidental
 * outside-tap while using a tool can't lose your place. Uses the SAME dim as the Tools folder grid
 * (0x4D / 30% black) so opening a tool from the folder keeps the identical background darkness.
 * Keep this alpha in sync with ToolsScreen's scrim.
 *
 * [onDismiss] is still wired to Back (via each overlay's BackHandler); the scrim itself just
 * consumes taps so nothing behind it (or a fall-through dismiss) fires.
 */
/** Page hosts keep their header/dock; Home callers retain an explicit, bounded modal. */
internal val LocalApprovedToolPage = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
internal fun Scrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
  if (LocalApprovedToolWorkspace.current) {
    content()
    return
  }
  val page = LocalApprovedToolPage.current
  if (!page) KeepInteractiveScreenAwake()
  Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()
      .background(if (page) IosBackground else Color(0x66000000))) { content() }
}

@Composable
internal fun ToolCard(maxWidth: Int = 860, onClose: (() -> Unit)? = null, content: @Composable () -> Unit) {
  if (LocalApprovedToolWorkspace.current) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) { content() }
    return
  }
  val page = LocalApprovedToolPage.current
  val cfg = androidx.compose.ui.platform.LocalConfiguration.current
  if (page) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 56.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
      if (onClose != null) ApprovedAppsButton("‹  Tools", onClick = onClose)
      content()
    }
  } else {
    Surface(color = IosCard, shape = RoundedCornerShape(28.dp), border = androidx.compose.foundation.BorderStroke(1.dp, IosDivider),
        modifier = Modifier.width((cfg.screenWidthDp * .72f).dp.coerceAtMost(maxWidth.dp)).heightIn(max = (cfg.screenHeightDp * .82f).dp)) {
      Column(Modifier.verticalScroll(rememberScrollState()).padding(36.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (onClose != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { CloseCircle(onClose) }
        content()
      }
    }
  }
}

internal val ToolWindow: Color @Composable get() = IosCard

/** Home modals keep their title; Tools detail has one heading supplied by its workspace. */
@Composable
private fun ToolTitle(title: String) {
  if (!LocalApprovedToolWorkspace.current) {
    Text(title, color = IosLabel, fontSize = 44.sp, lineHeight = 56.sp, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun ToolGroup(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
  Text(title, color = IosSecondary, fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.padding(start = 22.dp, bottom = 12.dp))
  Surface(color = IosCard, shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, IosDivider), modifier = Modifier.fillMaxWidth()) { Column(content = content) }
}

@Composable
private fun ToolValueRow(label: String, value: String, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().heightIn(min = 100.dp).tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f, onClick = onClick).padding(horizontal = 28.dp, vertical = 21.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = IosLabel, fontSize = 26.sp, lineHeight = 34.sp, modifier = Modifier.weight(1f))
    Text(value, color = IosSecondary, fontSize = 24.sp, lineHeight = 32.sp)
    Text("›", color = IosSecondary, fontSize = 28.sp, modifier = Modifier.padding(start = 22.dp))
  }
}

@Composable
private fun ToolDialog(title: String, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
  if (!LocalApprovedLauncherPageActive.current) return
  androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
    KeepInteractiveScreenAwake()
    Surface(color = IosCard, shape = RoundedCornerShape(28.dp), border = androidx.compose.foundation.BorderStroke(1.dp, IosDivider), modifier = Modifier.width(760.dp).heightIn(max = 980.dp)) {
      Column(Modifier.verticalScroll(rememberScrollState()).padding(36.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(title, color = IosLabel, fontSize = 34.sp, lineHeight = 46.sp, fontWeight = FontWeight.Medium)
        content()
      }
    }
  }
}
