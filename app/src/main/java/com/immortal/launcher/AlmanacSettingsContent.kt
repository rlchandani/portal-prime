/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.LocalAdvancedSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Scalar pack controls stay in the current Settings pane and share the remote registry. */
@Composable
internal fun AlmanacSettingsContent() {
  if (!LocalAdvancedSettings.current) {
    ApprovedSettingsAdvancedGate(LocalApprovedEnableAdvanced.current)
    return
  }
  val application = LocalContext.current.applicationContext
  val owner = LocalLifecycleOwner.current
  val pageActive = LocalApprovedLauncherPageActive.current
  val navigate = rememberApprovedSettingsNavigator()
  val scope = rememberCoroutineScope()
  val domain = SettingsDomains.calendarPacks
  var snapshot by remember(application) { mutableStateOf<CalendarPacks.Settings?>(null) }
  var saving by remember { mutableStateOf(false) }
  var failed by remember { mutableStateOf(false) }
  var retry by remember { mutableStateOf(0) }
  LaunchedEffect(application, owner, pageActive, retry) {
    if (!pageActive) return@LaunchedEffect
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      try {
        callbackFlow {
          // Subscribe before the read; conflate rapid remote edits without a polling loop.
          val stop = CalendarPacks.observeSnapshot(application) { trySend(Unit) }
          trySend(Unit)
          awaitClose { stop() }
        }.conflate().flowOn(Dispatchers.IO).collect {
          snapshot = withContext(Dispatchers.IO) { domain.load(application) }
          failed = false
        }
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { failed = true }
    }
  }
  Text("Adds optional daily lines to the Classic screensaver’s ambient dashboard. " +
      "Choose Classic and enable Ambient dashboard in Slideshow settings.",
      color = IosSecondary, fontSize = Sizes.rowSub)
  Card {
    NavRow("Slideshow settings") { navigate(approvedSettingsRoute("screensaver")) }
  }
  val current = snapshot
  if (current == null && !failed) Text("Loading Almanac settings…", color = IosSecondary)
  if (current != null) {
    SettingsList(domain, current, editsEnabled = !saving) { key, value ->
      if (!saving) {
        saving = true
        failed = false
        scope.launch {
          try {
            snapshot = withContext(Dispatchers.IO) {
              check(key in domain.apply(application, JSONObject().put(key, value)))
              domain.load(application)
            }
          } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { failed = true }
          finally { saving = false }
        }
      }
    }
  }
  if (failed) {
    Text("Almanac settings could not be updated. Try again.", color = IosSecondary)
    ActionPill("Try again", enabled = !saving) { failed = false; retry++ }
  }
}
