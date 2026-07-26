/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Calendar

/**
 * Screen brightness + auto-brightness, and a launcher "Night Shift" warm tint.
 *
 * Brightness and auto-brightness are real system settings ([Settings.System.SCREEN_BRIGHTNESS] /
 * SCREEN_BRIGHTNESS_MODE), written with the WRITE_SETTINGS app-op — the same permission
 * [SystemSounds] uses; the provisioning kit pre-grants it. Reads never need it.
 *
 * Night Shift is NOT a real panel color-temperature change (that needs a system HWC API a launcher
 * can't reach). Instead the launcher paints a translucent warm overlay over its own surface during a
 * nightly window, so the home screen / photo frame reads warmer at night. Purely an Immortal-drawn
 * layer; it doesn't affect other apps.
 */
object DisplayControl {

  private const val PREFS = "immortal_display"
  private const val KEY_NIGHT_SHIFT = "night_shift_enabled"
  private const val KEY_NIGHT_START = "night_shift_start_min" // minutes-of-day
  private const val KEY_NIGHT_END = "night_shift_end_min"
  private const val KEY_NIGHT_STRENGTH = "night_shift_strength" // 0..100

  private const val DEFAULT_START = 21 * 60 // 9:00 PM
  private const val DEFAULT_END = 7 * 60 // 7:00 AM
  private const val DEFAULT_STRENGTH = 45

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  // ---- WRITE_SETTINGS gate (same pattern as SystemSounds) ----
  fun canWrite(context: Context): Boolean =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(context) else true

  fun requestWriteAccess(context: Context) {
    runCatching {
      context.startActivity(
          Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }

  // ---- Brightness (0..100 for the UI; system stores 0..255) ----
  /** Current brightness as a 0..100 percent (reads the raw 0..255 system value). */
  fun brightnessPercent(context: Context): Int {
    val raw = runCatching {
      Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
    }.getOrDefault(128)
    return (raw * 100f / 255f).toInt().coerceIn(0, 100)
  }

  /** Set brightness from a 0..100 percent. Returns true if the write stuck. Also drops
   * auto-brightness to manual so the value isn't immediately overridden. */
  fun setBrightnessPercent(context: Context, percent: Int): Boolean {
    val raw = (percent.coerceIn(1, 100) * 255 / 100).coerceIn(1, 255)
    runCatching {
      Settings.System.putInt(
          context.contentResolver,
          Settings.System.SCREEN_BRIGHTNESS_MODE,
          Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
      Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
    }
    return brightnessPercent(context) == (raw * 100f / 255f).toInt().coerceIn(0, 100)
  }

  /** Whether auto-brightness (adaptive) is on. */
  fun autoBrightness(context: Context): Boolean =
      runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
      }.getOrDefault(false)

  fun setAutoBrightness(context: Context, on: Boolean): Boolean {
    runCatching {
      Settings.System.putInt(
          context.contentResolver,
          Settings.System.SCREEN_BRIGHTNESS_MODE,
          if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
          else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
    }
    return autoBrightness(context) == on
  }

  // ---- Night Shift (launcher warm overlay) — no WRITE_SETTINGS needed ----
  data class NightShift(
      val enabled: Boolean = false,
      val startMin: Int = DEFAULT_START,
      val endMin: Int = DEFAULT_END,
      val strength: Int = DEFAULT_STRENGTH, // 0..100
  )

  fun nightShift(context: Context): NightShift {
    val p = prefs(context)
    return NightShift(
        enabled = p.getBoolean(KEY_NIGHT_SHIFT, false),
        startMin = p.getInt(KEY_NIGHT_START, DEFAULT_START),
        endMin = p.getInt(KEY_NIGHT_END, DEFAULT_END),
        strength = p.getInt(KEY_NIGHT_STRENGTH, DEFAULT_STRENGTH),
    )
  }

  fun setNightShiftEnabled(context: Context, on: Boolean) =
      prefs(context).edit().putBoolean(KEY_NIGHT_SHIFT, on).apply()

  fun setNightShiftStart(context: Context, min: Int) =
      prefs(context).edit().putInt(KEY_NIGHT_START, min.coerceIn(0, 1439)).apply()

  fun setNightShiftEnd(context: Context, min: Int) =
      prefs(context).edit().putInt(KEY_NIGHT_END, min.coerceIn(0, 1439)).apply()

  fun setNightShiftStrength(context: Context, strength: Int) =
      prefs(context).edit().putInt(KEY_NIGHT_STRENGTH, strength.coerceIn(0, 100)).apply()

  /**
   * The warm-overlay alpha (0f..1f) that should be drawn RIGHT NOW: 0 outside the night window or
   * when disabled, else scaled by [NightShift.strength]. The window wraps midnight (start > end).
   */
  fun currentWarmAlpha(context: Context, now: Calendar = Calendar.getInstance()): Float {
    val ns = nightShift(context)
    if (!ns.enabled) return 0f
    val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    val inWindow =
        if (ns.startMin <= ns.endMin) nowMin in ns.startMin until ns.endMin
        else nowMin >= ns.startMin || nowMin < ns.endMin // wraps midnight
    if (!inWindow) return 0f
    // Cap the overlay so text stays readable: strength 100 → 0.35 alpha.
    return (ns.strength / 100f) * 0.35f
  }
}
