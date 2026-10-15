/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.ScreensaverConfig

/** Presentation policy shared by the native renderer and phone schema. Never mutates settings. */
object SettingsPresentation {
  private val advancedPages = setOf(
      "immich", "albums", "smb", "dav", "web", "multiroom", "mqtt", "boot",
      "alpaca", "options", "speed", "apk", "managed", "debug", "ha-entities")
  private val advancedControls = setOf(
      "immortal.hideStatusBar", "sources.albumRefresh", "screensaver.cacheEnabled",
      "screensaver.batterySaver", "screensaver.presenceMode", "screensaver.idleSleepMin",
      "screensaver.closeAppOnSleep", "screensaver.ambientDashboard", "screensaver.gestureWave",
      "dismiss.haPath", "welcome.durationMs", "welcome.voice", "welcome.greetingNight",
      "welcome.greetingMorning", "welcome.greetingAfternoon", "welcome.greetingEvening", "welcome.opacity",
      "immich.url", "immich.key", "immich.album", "smb.host", "smb.share", "smb.path",
      "smb.username", "smb.password", "dav.url", "dav.username", "dav.password", "web.url",
      "immortal.multiRoomEnabled", "immortal.snapcastHost", "immortal.maPort",
      "immortal.maUsername", "immortal.maPassword", "mqtt.enabled", "mqtt.host", "mqtt.port",
      "mqtt.username", "mqtt.password", "mqtt.useTls", "alpaca.key", "alpaca.secret",
      "options.type", "options.deltaMin", "options.deltaMax", "options.expiry", "options.symbol",
      "screensaver.albumRefreshMin", "screensaver.cacheBudgetGb", "screensaver.antiBurnIn",
      "mqtt.validateCert", "digitalclock.style", "digitalclock.color", "digitalclock.font",
      "digitalclock.glow", "debug.enabled", "ha.url", "ha.token")
  private val shortHelp = mapOf(
      "calendar.url" to "Home shows today and the next two days.",
      "calendar.widgetOn" to "Does not hide the calendar widget on Home.",
      "screensaver.enabled" to "Show photos when your Portal is idle.",
      "screensaver.mode" to "Choose how your photos appear.",
      "screensaver.photo_motion" to "Let photos move gently between transitions.",
      "screensaver.keep_people_in_frame" to "Keep detected faces visible as photos move.",
      "screensaver.clockFace" to "Choose how the time appears.",
      "screensaver.photoSource" to "Choose the photos on your screen.",
      "screensaver.dismissTarget" to "Choose what opens when you leave the screensaver.",
      "screensaver.overnightEnabled" to "Let the screen rest overnight.",
      "screensaver.sleepTimerEnabled" to "Turn the screen off after a set time.",
      "screensaver.pauseAudioOnSleep" to "Pause playback when the screen goes to sleep.",
      "display.auto" to "Adjust brightness to the room.",
      "display.nightShift" to "Use warmer colors after dark.",
      "welcome.enableTts" to "Read the greeting aloud.",
      "welcome.userName" to "Personalize your greeting.",
      "chime.quietHoursOn" to "Silence scheduled sounds. Matching times silence nothing.")

  fun isAdvanced(domainId: String, key: String): Boolean = "$domainId.$key" in advancedControls
  fun isAdvancedOption(domainId: String, key: String, value: String): Boolean =
      domainId == "screensaver" && key == "mode" && !ScreensaverConfig.isNativeStage(value)

  /** Preserve a saved legacy selection even when its other choices are hidden. */
  fun visibleEnumOptions(
      domainId: String, key: String, options: List<Pair<String, String>>,
      selected: String, advanced: Boolean,
  ): List<Pair<String, String>> = options.filter { (value, _) ->
    advanced || value == selected || !isAdvancedOption(domainId, key, value)
  }

  fun isAdvancedPage(page: String): Boolean = page in advancedPages
  @Suppress("UNUSED_PARAMETER")
  fun conciseHelp(domainId: String, key: String, fallback: String?): String =
      shortHelp["$domainId.$key"].orEmpty()
}
