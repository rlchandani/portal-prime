/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import android.content.Context

/**
 * The household's "Hey Jarvis on Hearthpane screens" choice (household scope: synced to every Portal in the home).
 * On by default (quiet hours still silence it). Edited only through [com.immortal.launcher.settings.JarvisSettings.domain]: on-device
 * Settings › Jarvis and the phone remote.
 */
internal object JarvisWakeConfig {
  private const val PREFS = "hearthpane_jarvis_wake"
  private const val KEY_ON_SCREENS = "wake_on_screens"
  private const val KEY_LEARN = "learn_from_conversations"

  /**
   * [learnFromConversations]: Jarvis personalization (docs/plans/jarvis-personalization.md), off until the owner
   * turns it on; [com.immortal.launcher.memory.JarvisMemory] reads it.
   */
  data class Settings(val wakeOnScreens: Boolean = true, val learnFromConversations: Boolean = false)

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(c: Context): Settings = prefs(c).let { p ->
    Settings(wakeOnScreens = p.getBoolean(KEY_ON_SCREENS, true), learnFromConversations = p.getBoolean(KEY_LEARN, false))
  }

  fun setWakeOnScreens(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ON_SCREENS, on).apply()

  fun setLearnFromConversations(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_LEARN, on).apply()
}
