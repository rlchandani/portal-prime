/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.DoorbellSettings
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val ROW_MIN_HEIGHT = 100.dp
private val LIST_MAX_HEIGHT = 520.dp

/**
 * Settings › Home Assistant › Doorbell: choose the doorbell button, the door camera and optional
 * motion sensors from what Home Assistant has. Every choice is written through
 * [DoorbellSettings.domain], like the phone remote's text fields.
 */
class DoorbellPickerActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // The sidebar highlights Home Assistant, the page these rows live on.
    if (!intent.hasExtra(APPROVED_SETTINGS_PAGE)) intent.putExtra(APPROVED_SETTINGS_PAGE, SettingsPageIds.HOME_ASSISTANT)
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        SettingsScaffold(title = "Doorbell devices", onBack = { finish() }) { DoorbellPickerContent() }
      }
    }
  }
}

@Composable
private fun DoorbellPickerContent() {
  val context = LocalContext.current
  var settings by remember { mutableStateOf(DoorbellConfig.load(context)) }
  var choices by remember { mutableStateOf<DoorbellDiscovery.Choices?>(null) }
  var loading by remember { mutableStateOf(true) }
  var reload by remember { mutableIntStateOf(0) }
  val configured = remember { HaConfig.isConfigured(context) }
  LaunchedEffect(reload) {
    if (!configured) { loading = false; return@LaunchedEffect }
    loading = true
    choices = withContext(Dispatchers.IO) { DoorbellDiscovery.fetch(context) }
    loading = false
  }
  fun apply(key: String, value: String) {
    DoorbellSettings.domain.apply(context, JSONObject().put(key, value))
    settings = DoorbellConfig.load(context)
  }
  Column {
    if (!configured) {
      Card { ApprovedEditorsEmpty("Connect Home Assistant first: turn on Advanced, then Home Assistant › Base URL and token.") }
      ApprovedSettingsTextButton("Set up Home Assistant") { context.startActivity(approvedSettingsIntent(context, "ha")) }
      return@Column
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      ApprovedEditorsButton(if (loading) "Loading…" else "Refresh", enabled = !loading) { reload++ }
    }
    val found = choices
    if (!loading && found == null) FooterNote("Couldn’t reach Home Assistant. Check the address and token.")
    if (found == null) return@Column
    PickerSection("Doorbell button", found.buttons, chosen = setOf(settings.trigger),
        empty = "No event or binary sensor entities found.") { id -> apply("trigger", if (id == settings.trigger) "" else id) }
    PickerSection("Door camera", found.cameras, chosen = setOf(settings.camera),
        empty = "No cameras found in Home Assistant.") { id -> apply("camera", if (id == settings.camera) "" else id) }
    val motion = DoorbellEntities.motionList(settings.motion)
    PickerSection("Motion sensors (optional)", found.motion, chosen = motion.toSet(),
        empty = "No motion sensors found.") { id ->
      val next = if (id in motion) motion - id else (motion + id).take(DoorbellEntities.MAX_MOTION)
      apply("motion", next.joinToString(", "))
    }
    FooterNote("Tap a chosen item again to clear it. Up to ${DoorbellEntities.MAX_MOTION} motion sensors.")
  }
}

@Composable
private fun PickerSection(
    title: String, options: List<DoorbellDiscovery.Option>, chosen: Set<String>, empty: String, onPick: (String) -> Unit,
) {
  Spacer(Modifier.height(24.dp))
  SectionLabel(title)
  Card {
    if (options.isEmpty()) ApprovedEditorsEmpty(empty)
    else LazyColumn(Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT)) {
      items(options, key = { it.entityId }) { option ->
        val picked = option.entityId in chosen
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = ROW_MIN_HEIGHT)
            .tvFocusableRow(focusFill = IosFocusTint, onClick = { onPick(option.entityId) })
            .padding(horizontal = 28.dp, vertical = 21.dp),
            verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(option.name, color = IosLabel, fontSize = 26.sp, lineHeight = 34.sp)
            Text(option.entityId, color = IosSecondary, fontSize = 21.sp, lineHeight = 29.sp)
          }
          Text(if (picked) "Chosen ✓" else "Choose", color = if (picked) IosSecondary else IosBlue,
              fontSize = 24.sp, lineHeight = 32.sp)
        }
        Divider()
      }
    }
  }
}
