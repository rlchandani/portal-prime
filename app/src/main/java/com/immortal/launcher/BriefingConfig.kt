/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context

/** When the morning briefing opens by itself. [wire] is what prefs and the phone remote store. */
internal enum class BriefingTrigger(val wire: String, val label: String) {
  /** After the alarm or the sunrise wake light is dismissed, and from the Good morning card. */
  ALARM("alarm", "Show at alarm time"),
  /** Only from the Good morning card on Home. */
  TAP("tap", "Only when I tap Good morning");

  companion object {
    fun fromWire(code: String?): BriefingTrigger? = entries.firstOrNull { it.wire == code }
  }
}

/**
 * This Portal's morning briefing preferences (Settings › Alarms & Wake › Morning briefing). Edited
 * only through [com.immortal.launcher.settings.BriefingSettings] (the settings registry). Quiet
 * hours and the voice are the chime's ([ChimeConfig]); day bookkeeping is in [BriefingDayMarks].
 */
internal object BriefingConfig {
  private const val PREFS = "immortal_briefing"
  private const val KEY_ENABLED = "enabled"
  private const val KEY_TRIGGER = "trigger"
  private const val KEY_SPEAK = "speak"

  data class Settings(
      /** Morning briefing on: the Good morning card shows on Home in the morning. */
      val enabled: Boolean = true,
      /** [BriefingTrigger.wire]: open by itself after the alarm, or only on tap. */
      val trigger: String = BriefingTrigger.ALARM.wire,
      /** Read the briefing aloud in the chime voice (never during quiet hours). */
      val speak: Boolean = false,
  ) {
    val triggerMode: BriefingTrigger get() = BriefingTrigger.fromWire(trigger) ?: BriefingTrigger.ALARM
  }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(c: Context): Settings {
    val p = prefs(c)
    val defaults = Settings()
    return Settings(
        enabled = p.getBoolean(KEY_ENABLED, defaults.enabled),
        trigger = BriefingTrigger.fromWire(p.getString(KEY_TRIGGER, null))?.wire ?: defaults.trigger,
        speak = p.getBoolean(KEY_SPEAK, defaults.speak),
    )
  }

  fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()

  /** Unknown values are ignored (the registry's coercer already rejects them). */
  fun setTrigger(c: Context, wire: String) {
    val mode = BriefingTrigger.fromWire(wire) ?: return
    prefs(c).edit().putString(KEY_TRIGGER, mode.wire).apply()
  }

  fun setSpeak(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_SPEAK, on).apply()
}
