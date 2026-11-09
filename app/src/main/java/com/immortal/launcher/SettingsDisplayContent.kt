/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class BrightnessSnapshot(
    val canWrite: Boolean,
    val brightness: Int,
    val autoBrightness: Boolean,
)

private data class DisplaySnapshot(
    val canWrite: Boolean,
    val brightness: Int,
    val autoBrightness: Boolean,
    val volume: Int,
    val nightShift: DisplayControl.NightShift,
)

/**
 * The "Display" detail: screen brightness + auto-brightness (real system settings via
 * [DisplayControl], needing the WRITE_SETTINGS grant), and Night Shift — a launcher-drawn warm tint
 * over a nightly window. Brightness rows gate on the write permission; if it's not held we show a
 * "grant" nav row that opens the system screen.
 */
/** Which parts of the Display editor a page shows; Media volume lives in Sounds. */
internal enum class DisplayPart { BRIGHTNESS, VOLUME, NIGHT_SHIFT }

@Composable
internal fun DisplaySection(parts: Set<DisplayPart>) {
  val context = LocalContext.current
  val writes = remember(context.applicationContext) { DisplayWrites.queue(context) }
  var snapshot by remember(context) { mutableStateOf<DisplaySnapshot?>(null) }
  var loadFailed by remember(context) { mutableStateOf(false) }
  var editRevision by remember(context) { mutableStateOf(0) }
  var brightnessRevision by remember(context) { mutableStateOf(0) }
  var confirmedBrightness by remember(context) { mutableStateOf<BrightnessSnapshot?>(null) }
  var brightnessError by remember(context) { mutableStateOf<String?>(null) }
  var brightnessReadback by remember(context) { mutableStateOf<Job?>(null) }
  var latestBrightnessEdit by remember(context) { mutableStateOf<DisplayEdit?>(null) }
  val scope = rememberCoroutineScope()
  val lifecycleOwner = LocalLifecycleOwner.current
  val pageActive = LocalApprovedLauncherPageActive.current
  LaunchedEffect(context, lifecycleOwner, pageActive) {
    if (!pageActive) return@LaunchedEffect
    // The retained pane owner follows both the parent lifecycle and pane activation. There is
    // one initial/resume load, and leaving RESUMED cancels publication of an unfinished read.
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      loadFailed = false
      val revision = editRevision
      val loaded = withContext(Dispatchers.IO) {
        // A pane can resume while its final drag value is still being applied in the background.
        writes.awaitIdle()
        runCatching {
          DisplaySnapshot(
              canWrite = DisplayControl.canWrite(context),
              brightness = DisplayControl.brightnessPercent(context),
              autoBrightness = DisplayControl.autoBrightness(context),
              volume = DisplayControl.mediaVolumePercent(context),
              nightShift = DisplayControl.nightShift(context),
          )
        }.getOrNull()
      }
      loadFailed = loaded == null
      // Keep real cached values during a refresh, but never replace a newer slider/toggle edit
      // with a system read that started before that edit.
      if (loaded != null && revision == editRevision) {
        snapshot = loaded
        confirmedBrightness = BrightnessSnapshot(loaded.canWrite, loaded.brightness, loaded.autoBrightness)
        brightnessError = null
      }
    }
  }
  val display = snapshot
  if (display == null) {
    // Until the first snapshot arrives there are no writable rows or invented control values.
    FooterNote(if (loadFailed) "Display settings could not be loaded. Reopen this page to try again."
        else "Loading display settings…")
    return
  }
  fun updateDisplay(update: (DisplaySnapshot) -> DisplaySnapshot) {
    snapshot = snapshot?.let(update)
    editRevision++
  }
  fun beginBrightnessEdit(edit: DisplayEdit) {
    latestBrightnessEdit = edit
    brightnessReadback?.cancel()
    brightnessRevision++
    brightnessError = null
  }
  fun reconcileBrightness(expected: DisplayEdit) {
    brightnessReadback?.cancel()
    val revision = brightnessRevision
    brightnessReadback = scope.launch {
      val actual = withContext(Dispatchers.IO) {
        writes.awaitIdle()
        runCatching {
          BrightnessSnapshot(
              DisplayControl.canWrite(context),
              DisplayControl.brightnessPercent(context),
              DisplayControl.autoBrightness(context),
          )
        }.getOrNull()
      }
      // A later brightness edit owns the UI; volume and tint edits must not invalidate this read.
      if (revision != brightnessRevision) return@launch
      val observed = actual ?: confirmedBrightness
      if (observed != null) {
        snapshot = snapshot?.copy(canWrite = observed.canWrite, brightness = observed.brightness,
            autoBrightness = observed.autoBrightness)
      }
      if (actual == null) {
        brightnessError = "Brightness change could not be verified."
      } else {
        confirmedBrightness = actual
        val applied = actual.canWrite && when (expected) {
          is DisplayEdit.Brightness -> {
            // Android stores 0..255; match the same integer quantization as DisplayControl.
            val raw = (expected.percent.coerceIn(1, 100) * 255 / 100).coerceIn(1, 255)
            !actual.autoBrightness && actual.brightness == (raw * 100f / 255f).toInt()
          }
          is DisplayEdit.AutoBrightness -> actual.autoBrightness == expected.enabled
          is DisplayEdit.Volume -> false
        }
        brightnessError = if (applied) null else "Brightness change could not be applied."
      }
    }
  }
  fun updateNightShift(update: (DisplayControl.NightShift) -> DisplayControl.NightShift) {
    updateDisplay { it.copy(nightShift = update(it.nightShift)) }
  }
  val brightness = display.brightness
  val volume = display.volume
  val ns = display.nightShift

  if (DisplayPart.BRIGHTNESS in parts) {
    ApprovedSettingsSectionLabel("Brightness")
    Card {
      if (!display.canWrite) {
        NavRow(
            title = "Allow brightness control",
            value = "Grant",
        ) { DisplayControl.requestWriteAccess(context) }
      } else {
        // Brightness slider row.
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Brightness", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.width(240.dp))
          androidx.compose.material3.Slider(
              value = brightness.toFloat(),
              onValueChange = {
                val percent = it.toInt()
                // A manual brightness write also turns adaptive brightness off.
                beginBrightnessEdit(DisplayEdit.Brightness(percent))
                updateDisplay { current -> current.copy(brightness = percent, autoBrightness = false) }
                writes.submit(DisplayEdit.Brightness(percent))
              },
              onValueChangeFinished = {
                // Finishing an older drag must not supersede a newer auto-brightness toggle.
                val edit = latestBrightnessEdit
                if (edit is DisplayEdit.Brightness) reconcileBrightness(edit)
              },
              valueRange = 1f..100f,
              colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = IosBlue,
                  activeTrackColor = IosBlue, inactiveTrackColor = IosDivider),
              modifier = Modifier.weight(1f).semantics { contentDescription = "Adjust brightness" },
          )
          Text("$brightness%", color = IosSecondary, fontSize = Sizes.rowValue,
              modifier = Modifier.width(90.dp), textAlign = TextAlign.End)
        }
        Divider()
        ToggleRow(
            title = "Auto-brightness",
            checked = display.autoBrightness,
            onChange = { on ->
              beginBrightnessEdit(DisplayEdit.AutoBrightness(on))
              updateDisplay { it.copy(autoBrightness = on) }
              writes.submit(DisplayEdit.AutoBrightness(on))
              reconcileBrightness(DisplayEdit.AutoBrightness(on))
            },
        )
      }
    }
    brightnessError?.let {
      Text(it, color = IosRed, fontSize = Sizes.rowSub,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
    FooterNote("Adjust the screen brightness, or let it adapt to the room automatically.")
  }
  if (DisplayPart.VOLUME in parts) {
    ApprovedSettingsSectionLabel("Volume")
    Card {
      Row(
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight)
              .padding(horizontal = 16.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Media volume", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.width(240.dp))
        androidx.compose.material3.Slider(
            value = volume.toFloat(),
            onValueChange = {
              val percent = it.toInt()
              updateDisplay { current -> current.copy(volume = percent) }
              writes.submit(DisplayEdit.Volume(percent))
            },
            valueRange = 0f..100f,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = IosBlue,
                activeTrackColor = IosBlue, inactiveTrackColor = IosDivider),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Adjust media volume" },
        )
        Text("$volume%", color = IosSecondary, fontSize = Sizes.rowValue,
            modifier = Modifier.width(90.dp), textAlign = TextAlign.End)
      }
    }
    FooterNote("Sets the media playback volume (music, videos, chimes).")
  }
  if (DisplayPart.NIGHT_SHIFT in parts) {
    SectionLabel("Night Shift")
    Card {
      ToggleRow(
          title = "Night Shift",
          checked = ns.enabled,
          onChange = { on ->
            DisplayControl.setNightShiftEnabled(context, on)
            updateNightShift { it.copy(enabled = on) }
          },
      )
      if (ns.enabled) {
        Divider()
        Stepper(
            label = "From",
            valueText = minuteLabel(ns.startMin),
            onMinus = {
              val next = stepSettingNumber(ns.startMin, -30, 0, 1439, wrap = true)
              DisplayControl.setNightShiftStart(context, next)
              updateNightShift { it.copy(startMin = next) }
            },
            onPlus = {
              val next = stepSettingNumber(ns.startMin, 30, 0, 1439, wrap = true)
              DisplayControl.setNightShiftStart(context, next)
              updateNightShift { it.copy(startMin = next) }
            },
        )
        Divider()
        Stepper(
            label = "Until",
            valueText = minuteLabel(ns.endMin),
            onMinus = {
              val next = stepSettingNumber(ns.endMin, -30, 0, 1439, wrap = true)
              DisplayControl.setNightShiftEnd(context, next)
              updateNightShift { it.copy(endMin = next) }
            },
            onPlus = {
              val next = stepSettingNumber(ns.endMin, 30, 0, 1439, wrap = true)
              DisplayControl.setNightShiftEnd(context, next)
              updateNightShift { it.copy(endMin = next) }
            },
        )
        Divider()
        Stepper(
            label = "Warmth",
            valueText = "${ns.strength}%",
            onMinus = {
              DisplayControl.setNightShiftStrength(context, ns.strength - 10)
              updateNightShift { it.copy(strength = (ns.strength - 10).coerceIn(0, 100)) }
            },
            onPlus = {
              DisplayControl.setNightShiftStrength(context, ns.strength + 10)
              updateNightShift { it.copy(strength = (ns.strength + 10).coerceIn(0, 100)) }
            },
        )
      }
    }
    FooterNote("Warms the screen with an amber tint during the night window, easier on the eyes after dark.")
  }
}

/** "h:mm a" / "HH:mm"-ish label for a minute-of-day (uses 12-hour with AM/PM for readability). */
private fun minuteLabel(min: Int): String {
  val h = (min / 60) % 24
  val m = min % 60
  val ampm = if (h < 12) "AM" else "PM"
  val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
  return "%d:%02d %s".format(h12, m, ampm)
}
