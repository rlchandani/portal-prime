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
import com.immortal.launcher.ui.theme.PortalPrimeTheme

/**
 * Activity wrappers for the Immortal settings sub-screens, so each is reached by a `NavSpec`
 * (launching an Activity) like every other settings sub-screen — collapsing the old in-file
 * `mutableStateOf` navigation onto the one nav model the Screensaver screen already uses. The screen
 * composables themselves live in [ImmortalSettingsActivity]; these are thin hosts (`onBack` = finish).
 */
class MultiRoomActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { MultiRoomScreen(onBack = { finish() }) } }
  }
}

class MqttActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { MqttScreen(onBack = { finish() }) } }
  }
}

class DeviceHealthActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { DeviceHealthScreen(onBack = { finish() }) } }
  }
}

class WorldClockActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { WorldClockScreen(onBack = { finish() }) } }
  }
}

class BootAppsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Selection state now lives inside BootAppsContent so it's self-contained wherever rendered
    // (standalone Activity or the inlined settings panel).
    setContent { PortalPrimeTheme(darkTheme = false) { BootAppsScreen(onBack = { finish() }) } }
  }
}
