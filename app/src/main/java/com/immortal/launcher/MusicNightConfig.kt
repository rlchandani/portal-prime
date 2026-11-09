/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context

/**
 * Settings › Music: "Quieter music during quiet hours" (the hours themselves are the chime's). Read
 * and written through [com.immortal.launcher.settings.MusicSettings.domain] (on-device and phone remote).
 */
object MusicNightConfig {
  private const val PREFS = "immortal_music_night"
  private const val KEY_ON = "quietCap"
  private const val KEY_LEVEL = "quietMaxLevel"

  data class Settings(
      /** Cap music volume while quiet hours are on. */
      val quietCap: Boolean = true,
      /** The loudest music may get then, of [MusicNightCapMath.SCALE]. */
      val quietMaxLevel: Int = MusicNightCapMath.DEFAULT_LEVEL,
  )

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(c: Context): Settings = prefs(c).let {
    Settings(quietCap = it.getBoolean(KEY_ON, true),
        quietMaxLevel = MusicNightCapMath.clampLevel(it.getInt(KEY_LEVEL, MusicNightCapMath.DEFAULT_LEVEL)))
  }

  fun setQuietCap(c: Context, v: Boolean) { prefs(c).edit().putBoolean(KEY_ON, v).apply() }

  fun setQuietMaxLevel(c: Context, v: Int) {
    prefs(c).edit().putInt(KEY_LEVEL, MusicNightCapMath.clampLevel(v)).apply()
  }
}
