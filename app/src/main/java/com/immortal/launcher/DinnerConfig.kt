/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context

/**
 * This Portal's Call everyone preferences. Quiet hours and chime volume are deliberately not
 * here: they are the chime's ([ChimeConfig]), shared with the hourly chime. Edited only through
 * [DinnerSettings.domain] (the settings registry), never directly from UI or routes.
 */
internal object DinnerConfig {
  private const val PREFS = "immortal_dinner"

  data class Settings(
      /** "Let other rooms call this Portal". Off: calls are turned away ("Calls are turned off there"). */
      val receive: Boolean = true,
      /** "Say the message out loud" after the chime. */
      val speak: Boolean = true,
  )

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(c: Context): Settings = prefs(c).let { Settings(receive = it.getBoolean("receive", true), speak = it.getBoolean("speak", true)) }

  fun setReceive(c: Context, on: Boolean) = prefs(c).edit().putBoolean("receive", on).apply()
  fun setSpeak(c: Context, on: Boolean) = prefs(c).edit().putBoolean("speak", on).apply()
}
