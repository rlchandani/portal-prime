/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * This Portal's Control Center settings, written only through
 * [com.immortal.launcher.settings.ControlCenterSettings.domain] (the settings registry), so the
 * on-device page and the phone remote validate the same way. The kids PIN is not a registry
 * setting: it is kept here only as a salted hash and never leaves the Portal.
 */
internal object ControlCenterConfig {
  private const val PREFS = "hearthpane_control_center"
  const val MODE_FULL = "full"
  const val MODE_KIDS = "kids"
  const val MODE_BANNERS = "banners"
  val MODES = listOf(MODE_FULL, MODE_KIDS, MODE_BANNERS)
  const val MIN_WARN_MIN = 1
  const val MAX_WARN_MIN = 120
  const val MIN_PIN = 4
  const val MAX_PIN = 8
  const val MIN_EXIT_S = 0
  const val MAX_EXIT_S = 180
  private const val SALT_BYTES = 16
  private const val HEX_RADIX = 16
  private const val BYTE_MASK = 0xff

  @Immutable
  data class Settings(
      val portalMode: String = MODE_FULL,
      val kidsHideLocks: Boolean = true,
      val kidsHideGarage: Boolean = true,
      val kidsHideSecurity: Boolean = true,
      val kidsHideCameras: Boolean = true,
      val kidsHideValve: Boolean = true,
      val kidsLockThermostat: Boolean = false,
      val askUnlock: Boolean = true,
      val askGarage: Boolean = true,
      val askValve: Boolean = true,
      val askScenes: Boolean = true,
      val bannersListen: Boolean = true,
      val bannersWarnings: Boolean = true,
      val warningAfterMin: Int = 10,
      val bannersInfo: Boolean = true,
      val criticalSoundAtNight: Boolean = false,
      val reducedMotion: Boolean = false,
      val darkTheme: Boolean = true,
      val pinLength: Int = MIN_PIN,
      val exitDelaySec: Int = 30,
  ) {
    val kids: Boolean get() = portalMode == MODE_KIDS
    val bannersOnly: Boolean get() = portalMode == MODE_BANNERS
  }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(context: Context): Settings {
    val p = prefs(context)
    val d = Settings()
    return Settings(
        portalMode = p.getString("portal_mode", d.portalMode)?.takeIf { it in MODES } ?: d.portalMode,
        kidsHideLocks = p.getBoolean("kids_locks", d.kidsHideLocks),
        kidsHideGarage = p.getBoolean("kids_garage", d.kidsHideGarage),
        kidsHideSecurity = p.getBoolean("kids_security", d.kidsHideSecurity),
        kidsHideCameras = p.getBoolean("kids_cameras", d.kidsHideCameras),
        kidsHideValve = p.getBoolean("kids_valve", d.kidsHideValve),
        kidsLockThermostat = p.getBoolean("kids_thermostat", d.kidsLockThermostat),
        askUnlock = p.getBoolean("ask_unlock", d.askUnlock),
        askGarage = p.getBoolean("ask_garage", d.askGarage),
        askValve = p.getBoolean("ask_valve", d.askValve),
        askScenes = p.getBoolean("ask_scenes", d.askScenes),
        bannersListen = p.getBoolean("banners_listen", d.bannersListen),
        bannersWarnings = p.getBoolean("banners_warn", d.bannersWarnings),
        warningAfterMin = p.getInt("warn_after_min", d.warningAfterMin).coerceIn(MIN_WARN_MIN, MAX_WARN_MIN),
        bannersInfo = p.getBoolean("banners_info", d.bannersInfo),
        criticalSoundAtNight = p.getBoolean("crit_sound_night", d.criticalSoundAtNight),
        reducedMotion = p.getBoolean("reduced_motion", d.reducedMotion),
        darkTheme = p.getBoolean("dark_theme", d.darkTheme),
        pinLength = p.getInt("pin_length", d.pinLength).coerceIn(MIN_PIN, MAX_PIN),
        exitDelaySec = p.getInt("exit_delay_s", d.exitDelaySec).coerceIn(MIN_EXIT_S, MAX_EXIT_S),
    )
  }

  private fun putBool(c: Context, key: String, v: Boolean) = prefs(c).edit().putBoolean(key, v).apply()
  private fun putInt(c: Context, key: String, v: Int) = prefs(c).edit().putInt(key, v).apply()

  fun setPortalMode(c: Context, v: String) { if (v in MODES) prefs(c).edit().putString("portal_mode", v).apply() }
  fun setKidsHideLocks(c: Context, v: Boolean) = putBool(c, "kids_locks", v)
  fun setKidsHideGarage(c: Context, v: Boolean) = putBool(c, "kids_garage", v)
  fun setKidsHideSecurity(c: Context, v: Boolean) = putBool(c, "kids_security", v)
  fun setKidsHideCameras(c: Context, v: Boolean) = putBool(c, "kids_cameras", v)
  fun setKidsHideValve(c: Context, v: Boolean) = putBool(c, "kids_valve", v)
  fun setKidsLockThermostat(c: Context, v: Boolean) = putBool(c, "kids_thermostat", v)
  fun setAskUnlock(c: Context, v: Boolean) = putBool(c, "ask_unlock", v)
  fun setAskGarage(c: Context, v: Boolean) = putBool(c, "ask_garage", v)
  fun setAskValve(c: Context, v: Boolean) = putBool(c, "ask_valve", v)
  fun setAskScenes(c: Context, v: Boolean) = putBool(c, "ask_scenes", v)
  fun setBannersListen(c: Context, v: Boolean) = putBool(c, "banners_listen", v)
  fun setBannersWarnings(c: Context, v: Boolean) = putBool(c, "banners_warn", v)
  fun setWarningAfterMin(c: Context, v: Int) = putInt(c, "warn_after_min", v.coerceIn(MIN_WARN_MIN, MAX_WARN_MIN))
  fun setBannersInfo(c: Context, v: Boolean) = putBool(c, "banners_info", v)
  fun setCriticalSoundAtNight(c: Context, v: Boolean) = putBool(c, "crit_sound_night", v)
  fun setReducedMotion(c: Context, v: Boolean) = putBool(c, "reduced_motion", v)
  fun setDarkTheme(c: Context, v: Boolean) = putBool(c, "dark_theme", v)
  fun setPinLength(c: Context, v: Int) = putInt(c, "pin_length", v.coerceIn(MIN_PIN, MAX_PIN))
  fun setExitDelaySec(c: Context, v: Int) = putInt(c, "exit_delay_s", v.coerceIn(MIN_EXIT_S, MAX_EXIT_S))

  // --- scenes previewed once (first run shows the preview, later runs are one tap) ----------

  fun scenesSeen(c: Context): Set<String> = prefs(c).getStringSet("scenes_seen", emptySet()).orEmpty().toSet()
  fun markSceneSeen(c: Context, id: String) = prefs(c).edit().putStringSet("scenes_seen", scenesSeen(c) + id).apply()

  // --- kids PIN: salted SHA-256, never logged, never sent ---------------------------------

  fun hasKidsPin(c: Context): Boolean = prefs(c).getString("kids_pin_hash", null) != null

  fun setKidsPin(c: Context, pin: String): Boolean {
    if (!CcPin.valid(pin)) return false
    val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes).let(::hex)
    prefs(c).edit().putString("kids_pin_salt", salt).putString("kids_pin_hash", CcPin.hash(salt, pin)).apply()
    return true
  }

  fun checkKidsPin(c: Context, pin: String): Boolean {
    val p = prefs(c)
    val salt = p.getString("kids_pin_salt", null) ?: return false
    val hash = p.getString("kids_pin_hash", null) ?: return false
    return MessageDigest.isEqual(hash.toByteArray(), CcPin.hash(salt, pin).toByteArray())
  }

  private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0') }
}

/** Pure PIN rules (unit tested). */
internal object CcPin {
  fun valid(pin: String): Boolean = pin.length in ControlCenterConfig.MIN_PIN..ControlCenterConfig.MAX_PIN && pin.all { it.isDigit() }

  fun hash(salt: String, pin: String): String =
      MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
}
