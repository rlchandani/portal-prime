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
  private const val KEY_CLOSER = "closer_reading"

  /**
   * [learnFromConversations]: Jarvis personalization (docs/plans/jarvis-personalization.md), off until the owner
   * turns it on; [com.immortal.launcher.memory.JarvisMemory] reads it. [closerReading] is plan D3's own switch under
   * it: whether Jarvis may also send what the person said (their words only, as text) for a closer read. On by
   * default (the plan's recommendation), and it only matters while learning is on.
   */
  data class Settings(
      val wakeOnScreens: Boolean = true,
      val learnFromConversations: Boolean = false,
      val closerReading: Boolean = true,
  )

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(c: Context): Settings = prefs(c).let { p ->
    Settings(wakeOnScreens = p.getBoolean(KEY_ON_SCREENS, true), learnFromConversations = p.getBoolean(KEY_LEARN, false),
        closerReading = p.getBoolean(KEY_CLOSER, true))
  }

  fun setWakeOnScreens(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ON_SCREENS, on).apply()

  fun setLearnFromConversations(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_LEARN, on).apply()

  fun setCloserReading(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_CLOSER, on).apply()
}
