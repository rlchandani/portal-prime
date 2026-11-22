/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlinx.coroutines.delay

/**
 * Full-screen Home Assistant dashboard — approved redesign "A · Control Center sibling"
 * (mocks/ha-dashboard-20261122): rooms and scenes on the side (sideways strips on the Mini
 * upright), the chosen room's tiles in a dense 4/3/2-column grid, light and dark from Appearance,
 * and a state for loading, offline, sign-in, no rooms, empty room, failed actions and not set up.
 *
 * Entry: the Apps tab's "Home Assistant" tile (once HA is connected), "Home Assistant dashboard" in Settings → Home Assistant
 * and Control Center settings, fleet `open_screen` "ha_dashboard".
 */
class HaDashboardActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        HaDashboardTheme { HaDashboardHost(onBack = ::finish, onSettings = ::openSettings) }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    applyToolsDisplayBars(this)
  }

  private fun openSettings() {
    runCatching { startApprovedInternalActivity(Intent(this, HaSettingsActivity::class.java)) }
        .onFailure { Log.w(TAG, "open HA settings", it) }
  }

  private companion object { const val TAG = "HaDashboard" }
}

@Composable
private fun HaDashboardHost(onBack: () -> Unit, onSettings: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val controller = remember { HaDashboardController(context, scope) }
  val owner = LocalLifecycleOwner.current
  // Poll only while the screen is visible; coming back from Settings re-reads the config.
  LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { controller.poll() } }
  val now by produceState(System.currentTimeMillis()) {
    while (true) {
      value = System.currentTimeMillis()
      delay(MINUTE_MS - value % MINUTE_MS)
    }
  }
  var nowPlaying by remember { mutableStateOf(NowPlayingHub.current) }
  DisposableEffect(Unit) {
    val l = NowPlayingHub.Listener { nowPlaying = it }
    NowPlayingHub.addListener(l)
    onDispose { NowPlayingHub.removeListener(l) }
  }
  val use24h = remember(now / MINUTE_MS) { ImmortalSettings.use24HourClock(context) }
  val view = haDashboardView(controller.state, controller.session, now, use24h)
  val actions = remember(controller) {
    HaDashboardActions(back = onBack, settings = onSettings, retry = controller::retry, select = controller::select,
        scene = { controller.run(HaDashboardSession.Action.Scene(it.id, it.name)) }, retryToast = controller::retryToast,
        room = HaRoomCallbacks(run = controller::run, retry = controller::retry, media = { m ->
          when (m) {
            HaMediaAction.PLAY_PAUSE -> NowPlayingHub.playPause()
            HaMediaAction.NEXT -> NowPlayingHub.next()
            HaMediaAction.PREVIOUS -> NowPlayingHub.previous()
          }
        }))
  }
  HaDashboardScreen(view, nowPlaying, actions)
}

private const val MINUTE_MS = 60_000L
