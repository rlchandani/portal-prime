/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import android.content.Context

/**
 * This Portal's "Hey Jarvis on Hearthpane screens" choice; per room, never shared. On by default (quiet hours
 * still silence it). Edited only through [com.immortal.launcher.settings.JarvisSettings.domain]: on-device
 * Settings › Jarvis and the phone remote.
 */
internal object JarvisWakeConfig {
  private const val PREFS = "hearthpane_jarvis_wake"
  private const val KEY_ON_SCREENS = "wake_on_screens"

  data class Settings(val wakeOnScreens: Boolean = true)

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(c: Context): Settings = Settings(wakeOnScreens = prefs(c).getBoolean(KEY_ON_SCREENS, true))

  fun setWakeOnScreens(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ON_SCREENS, on).apply()
}
