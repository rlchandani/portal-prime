/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context

/**
 * This Portal's "Music follows you" choice ([FollowMeMode]); per room, never shared. Edited only
 * through [com.immortal.launcher.settings.FollowMeSettings.domain] (on-device Settings › Music and
 * the phone remote).
 */
internal object FollowMeConfig {
  private const val PREFS = "hearthpane_follow_me"
  private const val KEY_MODE = "mode"

  data class Settings(
      /** [FollowMeMode.wire]: "ask" (default), "auto" or "off". */
      val mode: String = FollowMeMode.ASK.wire,
  )

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(c: Context): Settings = Settings(mode = FollowMeMode.fromWire(prefs(c).getString(KEY_MODE, null)).wire)

  fun mode(c: Context): FollowMeMode = FollowMeMode.fromWire(load(c).mode)

  fun setMode(c: Context, wire: String) = prefs(c).edit().putString(KEY_MODE, FollowMeMode.fromWire(wire).wire).apply()
}
