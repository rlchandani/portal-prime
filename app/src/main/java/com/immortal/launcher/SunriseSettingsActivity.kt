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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.util.Calendar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Set the sunrise alarm: time, which days, ramp length, and the optional chime. */
class SunriseSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { SunriseScreen() } }
  }
}

private val DAY_LABELS =
    listOf(
        Calendar.MONDAY to "M", Calendar.TUESDAY to "T", Calendar.WEDNESDAY to "W",
        Calendar.THURSDAY to "T", Calendar.FRIDAY to "F", Calendar.SATURDAY to "S",
        Calendar.SUNDAY to "S")

@Composable
private fun SunriseScreen() {
  val activity = LocalContext.current as? Activity
  SettingsScaffold(
      title = "Sunrise Alarm",
      subtitle = "The screen brightens gradually to wake you, with an optional gentle chime.",
      onBack = { activity?.finish() },
  ) { SunriseContent() }
}

/** Inlinable body of the Sunrise settings screen — rendered both by [SunriseScreen] (standalone
 *  Activity host) and by the two-panel settings detail panel. */
@Composable
internal fun SunriseContent() {
  val context = LocalContext.current
  val application = context.applicationContext
  val writes = remember(application) { SunriseSettingsWrites.queue(application) }
  var settings by remember(application) { mutableStateOf(SunriseConfig.load(application)) }
  var saveState by remember { mutableStateOf(SettingsSaveState()) }
  val applying = saveState.saving
  val completion = rememberCoroutineScope()
  val lifecycleOwner = LocalLifecycleOwner.current
  val pageActive = LocalApprovedLauncherPageActive.current
  val rampSpec = remember {
    SettingsDomains.sunrise.specs.filterIsInstance<com.immortal.launcher.settings.IntSpec<SunriseConfig.Config>>()
        .single { it.key == "rampMinutes" }
  }
  // Use each registry predicate, keeping native and remote child availability in sync.
  val visibleKeys = remember(context, settings) {
    SettingsDomains.sunrise.specs.filter { it.visibleWhen(context, settings) }.map { it.key }.toSet()
  }
  suspend fun publishAfterSave(revision: Int) {
    try {
      writes.awaitIdle()
      val loaded = withContext(Dispatchers.IO) { SunriseConfig.load(application) }
      if (revision == saveState.revision) {
        settings = loaded
        saveState = saveState.completeRead(revision, writes.failureCount, writes.lastSaveFailed)
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
  fun submit(edit: () -> Unit) {
    if (saveState.saving) return
    saveState = saveState.beginEdit(writes.failureCount)
    val revision = saveState.revision
    edit()
    completion.launch { publishAfterSave(revision) }
  }
  fun apply(key: String, value: Any) = submit { writes.submit(key, value) }

  Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
    Column(Modifier.semantics { contentDescription = when {
      applying -> "Saving Sunrise settings"
      saveState.failed -> "Sunrise save unavailable"
      else -> "Sunrise settings ready"
    } }) {
      FooterNote(when {
        applying -> "Saving changes…"
        saveState.failed -> "Some changes couldn't be saved. Try again."
        !settings.enabled -> "Turn on Sunrise alarm to set your wake time, days and light ramp."
        else -> "Choose your wake time and gradual light ramp."
      })
    }
    Card {
      ToggleRow("Enabled", settings.enabled, enabled = !applying) { apply("enabled", it) }
    }
    if ("hour" in visibleKeys || "minute" in visibleKeys) {
      Card {
        ApprovedClockTimeSetting(
            title = "Wake time",
            value = settings.hour * 60 + settings.minute,
            valueLabel = "%02d:%02d".format(settings.hour, settings.minute),
            help = "Choose your wake time with the clock or enter an exact hour and minute.",
            enabled = !applying,
        ) { minutes ->
          val hour = minutes / 60
          val minute = minutes % 60
          if (hour != settings.hour || minute != settings.minute) {
            submit { writes.submitTime(hour, minute) }
          }
        }
      }
      // Weekdays are a bespoke set; follow the same parent gate as the wake time.
      SectionLabel("Wake on")
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DAY_LABELS.forEach { (day, label) ->
          val on = day in settings.days
          Surface(color = if (on) IosBlue else IosDivider, shape = CircleShape,
              modifier = Modifier.size(68.dp).semantics {
                contentDescription = java.text.DateFormatSymbols().weekdays[day]
              }.then(if (applying) Modifier.semantics { disabled() } else
                  Modifier.tvFocusable(CircleShape, focusScale = 1f) {
                    val days = if (on) settings.days - day else settings.days + day
                    submit { writes.submitDays(days) }
                  })) {
            Text(label, color = if (on) androidx.compose.material3.MaterialTheme.colorScheme.onPrimary else IosLabel,
                fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.padding(14.dp))
          }
        }
      }
    }
    // Bounds, help and visibility come from the same spec as the remote numeric picker.
    if ("rampMinutes" in visibleKeys) {
      Card {
        ApprovedIntSetting(rampSpec, settings.rampMinutes, rampSpec.help, !applying) {
          apply("rampMinutes", it)
        }
      }
    }
    if ("chime" in visibleKeys) {
      Card {
        ToggleRow("Chime at the end", settings.chime, enabled = !applying) { apply("chime", it) }
      }
    }
    val next = remember(settings) { SunriseConfig.nextTrigger(settings) }
    Text(if (next != null)
        "Next: " + java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(next)) else "Alarm is off.",
        color = IosBlue, fontSize = Sizes.rowValue)
    Surface(color = IosBlue, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
            .then(if (applying) Modifier.semantics { disabled() } else
                Modifier.tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) {
                  context.startActivity(Intent(context, WakeLightActivity::class.java)
                      .putExtra(WakeLightActivity.EXTRA_RAMP_MIN, 1)
                      .putExtra(WakeLightActivity.EXTRA_CHIME, settings.chime))
                })) {
      Text("Preview (1-min ramp)", color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
          fontSize = 24.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
          modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth())
    }
  }
}

