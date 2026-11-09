/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.immortal.launcher.settings.SettingsDomains
import org.json.JSONObject

@Composable
internal fun ApprovedTouchSounds() {
  val context = LocalContext.current
  val writes = remember(context.applicationContext) { SystemSoundsWrites.queue(context) }
  val completion = rememberCoroutineScope()
  var snapshot by remember(context) { mutableStateOf<SystemTouchSoundsSnapshot?>(null) }
  var failed by remember(context) { mutableStateOf(false) }
  var applying by remember(context) { mutableStateOf(false) }
  var editRevision by remember(context) { mutableStateOf(0) }
  val lifecycleOwner = LocalLifecycleOwner.current
  val pageActive = LocalApprovedLauncherPageActive.current
  suspend fun readSounds() = loadSystemTouchSounds {
    SystemTouchSoundsSnapshot(SystemSounds.canWrite(context), SystemSounds.touchSoundsEnabled(context))
  }
  LaunchedEffect(context, lifecycleOwner, pageActive) {
    if (!pageActive) return@LaunchedEffect
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      val revision = editRevision
      writes.awaitIdle()
      val loaded = readSounds()
      if (revision == editRevision) {
        failed = loaded == null
        if (loaded != null) snapshot = loaded
      }
    }
  }
  val current = snapshot
  if (current == null) {
    FooterNote(if (failed) "Touch sound change unavailable" else "Loading touch sound settings…")
    return
  }
  SectionLabel("Options")
  Card {
    if (!current.canWrite) {
      NavRow("Allow touch sound control", value = "Grant") { SystemSounds.requestWriteAccess(context) }
    } else {
      ToggleRow("Touch sounds", current.enabled, enabled = !applying) { checked ->
        if (!applying) {
          applying = true
          failed = false
          editRevision++
          snapshot = current.copy(enabled = checked)
          writes.submit(checked)
          completion.launch {
            try {
              writes.awaitIdle()
              val loaded = readSounds()
              failed = loaded == null || loaded.enabled != checked
              // A failed read-back must not leave an optimistic value presented as real state.
              snapshot = loaded
            } finally { applying = false }
          }
        }
      }
    }
  }
  if (applying) FooterNote("Applying touch sound setting…")
  if (failed) FooterNote("Touch sound change unavailable")
  FooterNote("Plays the system tap sound on touch. Android may ask you to allow modifying system settings.")
}

@Composable
internal fun ApprovedManagedSettingsPage() {
  val context = LocalContext.current
  com.immortal.launcher.settings.SettingsRegistry.domains.forEach { raw ->
    @Suppress("UNCHECKED_CAST")
    val domain = raw as com.immortal.launcher.settings.SettingsDomain<Any>
    val snapshot = domain.load(context)
    val controls = domain.specs.filter { "${domain.id}.${it.key}" in ApprovedManagedControls }
    if (controls.isNotEmpty()) {
      SectionLabel(domain.title)
      Card {
        controls.forEachIndexed { index, spec ->
          if (index > 0) Divider()
          val meta = spec.metaJson(context, snapshot)
          Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight)
              .padding(horizontal = 28.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            ApprovedSettingsLabel(meta?.optString("title") ?: spec.key,
                "Managed by the existing app · preserved without a new switch", Modifier.weight(1f))
            Text(meta?.optString("display")?.takeIf { it.isNotBlank() }
                ?: meta?.opt("value")?.toString().orEmpty(), color = IosSecondary, fontSize = Sizes.rowValue)
          }
        }
      }
    }
  }
  FooterNote("Legacy appearance values remain stored and unchanged.")
}

// ---------------------------------------------------------------------------
// Debug toggle row (uses a local pref; only relevant in debug builds)
// ---------------------------------------------------------------------------

private const val PREFS_DEBUG = "immortal_debug"

@Composable
internal fun IosDebugRow(context: android.content.Context) {
  val prefs = remember { context.getSharedPreferences(PREFS_DEBUG, android.content.Context.MODE_PRIVATE) }
  var debug by remember { mutableStateOf(prefs.getBoolean("debug_mode", false)) }
  ToggleRow(
      title = "Debug Mode",
      checked = debug,
      onChange = {
        debug = it
        prefs.edit().putBoolean("debug_mode", it).apply()
      },
  )
}

/** Device-name edits use the registry; saved readback stays current without replacing an open draft. */
@Composable
internal fun DeviceNameSection() {
  val context = LocalContext.current.applicationContext
  var settings by remember(context) { mutableStateOf(FleetConfig.loadName(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(context, lifecycleOwner) {
    var stopObserving: (() -> Unit)? = null
    fun stop() {
      stopObserving?.invoke()
      stopObserving = null
    }
    fun start() {
      if (stopObserving == null) {
        stopObserving = FleetConfig.observeName(context) { settings = FleetConfig.loadName(context) }
        settings = FleetConfig.loadName(context)
      }
    }
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> start()
        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stop()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      stop()
    }
  }
  SettingsList(SettingsDomains.fleet, settings) { key, value ->
    SettingsDomains.fleet.apply(context, JSONObject().put(key, value))
  }
  Spacer(Modifier.size(16.dp))
  Card { ApprovedSourcesInfoRow("Model", android.os.Build.MODEL) }
}

/**
 * "Quick buttons" section: a centered overlay button cluster at the top of the screen (v1: an
 * app switcher). Enable it, and choose whether it shows only while the top bar is revealed
 * (default) or always.
 */
@Composable
internal fun QuickButtonsSection() {
  val context = LocalContext.current
  var settings by remember(context) { mutableStateOf(QuickBarConfig.load(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(context, lifecycleOwner) {
    var stopObserving: (() -> Unit)? = null
    fun stop() {
      stopObserving?.invoke()
      stopObserving = null
    }
    fun start() {
      if (stopObserving == null) {
        stopObserving = QuickBarConfig.observe(context) { settings = QuickBarConfig.load(context) }
        settings = QuickBarConfig.load(context)
      }
    }
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> start()
        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stop()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      stop()
    }
  }

  ApprovedSettingsSectionLabel("Quick buttons")
  // Rendered from the `quickbar` registry domain (the same specs the phone remote uses), so the
  // toggle and its gated "Always show" can't drift from the remote's version. Apply routes through
  // the domain so its onApplied (reconcile + overlay refresh) fires here too.
  SettingsList(SettingsDomains.quickbar, settings) { k, v ->
    SettingsDomains.quickbar.apply(context, JSONObject().put(k, v))
  }
  Text(
      "Needs the accessibility-based top-bar watch enabled during setup. The switcher shows your " +
          "recently used apps; tap one to switch.",
      color = IosSecondary,
      fontSize = Sizes.rowSub,
      modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
  )
}
