/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context

/**
 * This Portal's doorbell choices: which Home Assistant entity is the doorbell press, which camera
 * shows the door, optional motion sensors, the chime and the per-camera cooldown. The Home
 * Assistant address and token stay in [HaConfig]. Edited only through
 * [com.immortal.launcher.settings.DoorbellSettings.domain] (the settings registry), never directly
 * from UI or routes.
 */
internal object DoorbellConfig {
  private const val PREFS = "immortal_doorbell"
  private const val KEY_LISTEN = "listen"
  private const val KEY_TRIGGER = "trigger"
  private const val KEY_CAMERA = "camera"
  private const val KEY_MOTION = "motion"
  private const val KEY_CHIME = "chime"
  private const val KEY_COOLDOWN = "cooldownSec"

  const val DEFAULT_COOLDOWN_SEC = 30
  const val MIN_COOLDOWN_SEC = 10
  const val MAX_COOLDOWN_SEC = 600
  const val COOLDOWN_STEP_SEC = 10

  data class Settings(
      /** "Watch for the doorbell here": this Portal keeps its own Home Assistant connection open. */
      val listen: Boolean = true,
      /** The doorbell press: an `event.*` or `binary_sensor.*` entity id, or blank. */
      val trigger: String = "",
      /** The door camera: a `camera.*` entity id, or blank (the card then shows no picture). */
      val camera: String = "",
      /** Optional motion sensors, comma separated `binary_sensor.*` ids. */
      val motion: String = "",
      /** Play the doorbell chime (quiet hours always make it visual only). */
      val chime: Boolean = true,
      /** Per-camera cooldown between alerts, in seconds. */
      val cooldownSec: Int = DEFAULT_COOLDOWN_SEC,
  ) {
    /** Every entity this Portal watches (the press plus the motion sensors). */
    val watched: List<String>
      get() = (listOfNotNull(trigger.takeIf(DoorbellEntities::isTrigger)) + DoorbellEntities.motionList(motion)).distinct()
  }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(c: Context): Settings = prefs(c).let { p ->
    Settings(
        listen = p.getBoolean(KEY_LISTEN, true),
        trigger = p.getString(KEY_TRIGGER, "").orEmpty(),
        camera = p.getString(KEY_CAMERA, "").orEmpty(),
        motion = p.getString(KEY_MOTION, "").orEmpty(),
        chime = p.getBoolean(KEY_CHIME, true),
        cooldownSec = p.getInt(KEY_COOLDOWN, DEFAULT_COOLDOWN_SEC).coerceIn(MIN_COOLDOWN_SEC, MAX_COOLDOWN_SEC),
    )
  }

  fun setListen(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_LISTEN, on).apply()
  fun setTrigger(c: Context, id: String) = prefs(c).edit().putString(KEY_TRIGGER, id.trim()).apply()
  fun setCamera(c: Context, id: String) = prefs(c).edit().putString(KEY_CAMERA, id.trim()).apply()
  fun setMotion(c: Context, ids: String) =
      prefs(c).edit().putString(KEY_MOTION, DoorbellEntities.motionList(ids).joinToString(", ")).apply()
  fun setChime(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_CHIME, on).apply()
  fun setCooldown(c: Context, sec: Int) =
      prefs(c).edit().putInt(KEY_COOLDOWN, sec.coerceIn(MIN_COOLDOWN_SEC, MAX_COOLDOWN_SEC)).apply()
}
