/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Automatic cues have two asynchronous boundaries; manual previews bypass this policy. */
internal class AutomaticTimeSpeech(
    private val loadSettings: () -> ChimeConfig.Settings,
    private val nowMinute: () -> Int,
) {
  fun playbackSettings(): ChimeConfig.Settings? {
    val settings = loadSettings()
    if (!settings.spokenTimeOn || settings.volume <= 0) return null
    val quiet = settings.quietHoursOn && ChimeConfig.inQuietWindow(
        nowMinute(), settings.quietStartMin, settings.quietEndMin)
    return settings.takeUnless { quiet }
  }

  fun deliverOrCancel(cancel: () -> Unit, deliver: (ChimeConfig.Settings) -> Unit) {
    val settings = playbackSettings()
    if (settings == null) cancel() else deliver(settings)
  }
}
