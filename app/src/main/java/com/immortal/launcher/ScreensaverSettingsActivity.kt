/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.immortal.launcher.settings.SettingsDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The old standalone screensaver settings entry. The same rows now live in the split-view
 * Settings (Photo Frame, Night & Sleep, Sounds › Ambient soundscape, Widgets & Calendar ›
 * Calendar), so this forwards to Photo Frame and finishes.
 */
class ScreensaverSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    startActivity(settingsPageIntent(this, SettingsPageIds.FRAME))
    finish()
  }
}

/** Screensaver writes share one off-main-thread queue; rows pause while a write is saving. */
internal data class ScreensaverSettingsEditor(
  val settings: MutableState<ScreensaverConfig.Settings>,
  val applying: MutableState<Boolean>,
  val failed: MutableState<Boolean>,
  val apply: (SettingsDomain<ScreensaverConfig.Settings>, String, Any) -> Unit,
)

/** Pane-owned readbacks cancel on departure; accepted writes belong to the process. */
@Composable
internal fun rememberScreensaverEditor(): ScreensaverSettingsEditor {
  val application = LocalContext.current.applicationContext
  val writes = remember(application) { ScreensaverSettingsWrites.queue(application) }
  val state = remember(application) { mutableStateOf(ScreensaverConfig.load(application)) }
  val applying = remember { mutableStateOf(true) }
  val failed = remember { mutableStateOf(false) }
  var editRevision by remember { mutableStateOf(0) }
  val completion = rememberCoroutineScope()
  val lifecycleOwner = LocalLifecycleOwner.current
  val pageActive = LocalApprovedLauncherPageActive.current
  suspend fun readSnapshot() = withContext(Dispatchers.IO) { ScreensaverConfig.load(application) }
  LaunchedEffect(application, lifecycleOwner, pageActive) {
    if (!pageActive) return@LaunchedEffect
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      val revision = editRevision
      val failures = writes.failureCount
      try {
        writes.awaitIdle()
        val loaded = readSnapshot()
        if (revision == editRevision) {
          state.value = loaded
          failed.value = writes.failureCount != failures
        }
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { if (revision == editRevision) failed.value = true }
      finally { if (revision == editRevision) applying.value = false }
    }
  }
  return ScreensaverSettingsEditor(state, applying, failed) { domain, key, value ->
    applying.value = true
    failed.value = false
    val revision = ++editRevision
    val failures = writes.failureCount
    writes.submit(ScreensaverSettingEdit(domain.id, key, value))
    completion.launch {
      try {
        writes.awaitIdle()
        val loaded = readSnapshot()
        if (revision == editRevision) {
          state.value = loaded
          failed.value = writes.failureCount != failures
        }
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { if (revision == editRevision) failed.value = true }
      finally { if (revision == editRevision) applying.value = false }
    }
  }
}
