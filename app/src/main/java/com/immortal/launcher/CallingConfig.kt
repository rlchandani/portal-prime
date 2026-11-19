/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import androidx.compose.runtime.Immutable

/**
 * This Portal's Calling preferences. Quiet hours themselves are the chime's ([ChimeConfig]),
 * shared with the hourly chime and Call everyone. Written only through
 * [com.immortal.launcher.settings.CallingSettings.domain] (the settings registry), so the
 * on-device page and the phone remote validate identically.
 */
internal object CallingConfig {
  private const val PREFS = "hearthpane_calling"
  const val MIN_RING_VOLUME = 0
  const val MAX_RING_VOLUME = 100
  const val RING_VOLUME_STEP = 10
  /** Mock: "Ring volume" slider at 60%. */
  const val DEFAULT_RING_VOLUME = 60

  @Immutable
  data class Settings(
      val whoCanCall: String = CallWhoCanCall.HOME.wire,
      val ringtone: String = CallRingtone.HEARTH.wire,
      val ringVolume: Int = DEFAULT_RING_VOLUME,
      val quietMode: String = CallQuietMode.BLOCK.wire,
      val quality: String = CallQuality.AUTO.wire,
      /** Mock Decisions: "Allow drop-ins" is off on every Portal until turned on here. */
      val dropInsOn: Boolean = false,
      /** This Portal's column of the drop-in grid ([DropInGrid] text). */
      val dropInRooms: String = "",
      /** Settings › Calls › Phones: paired phones (Portal Toolkit, home Wi-Fi) may ring this Portal. */
      val phonesOn: Boolean = true,
      /** Settings › Calls › Groups: this Portal may add people and be added to group calls. */
      val groupsOn: Boolean = true,
  ) {
    val who: CallWhoCanCall get() = CallWhoCanCall.fromWire(whoCanCall)
    val tone: CallRingtone get() = CallRingtone.fromWire(ringtone)
    val quiet: CallQuietMode get() = CallQuietMode.fromWire(quietMode)
    val callQuality: CallQuality get() = CallQuality.fromWire(quality)
    val dropIns: Map<String, DropInPolicy> get() = DropInGrid.decode(dropInRooms) ?: emptyMap()
  }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(c: Context): Settings = prefs(c).let { p ->
    Settings(
        whoCanCall = p.getString("whoCanCall", null) ?: CallWhoCanCall.HOME.wire,
        ringtone = p.getString("ringtone", null) ?: CallRingtone.HEARTH.wire,
        ringVolume = p.getInt("ringVolume", DEFAULT_RING_VOLUME).coerceIn(MIN_RING_VOLUME, MAX_RING_VOLUME),
        quietMode = p.getString("quietMode", null) ?: CallQuietMode.BLOCK.wire,
        quality = p.getString("quality", null) ?: CallQuality.AUTO.wire,
        dropInsOn = p.getBoolean("dropInsOn", false),
        dropInRooms = p.getString("dropInRooms", null).orEmpty(),
        phonesOn = p.getBoolean("phonesOn", true),
        groupsOn = p.getBoolean("groupsOn", true),
    )
  }

  /** Calls [onChange] after any Calling preference changes; returns the unsubscribe. */
  fun observe(c: Context, onChange: () -> Unit): () -> Unit {
    val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
    prefs(c).registerOnSharedPreferenceChangeListener(listener)
    return { prefs(c).unregisterOnSharedPreferenceChangeListener(listener) }
  }

  fun setWhoCanCall(c: Context, v: String) = prefs(c).edit().putString("whoCanCall", v).apply()
  fun setRingtone(c: Context, v: String) = prefs(c).edit().putString("ringtone", v).apply()
  fun setRingVolume(c: Context, v: Int) = prefs(c).edit().putInt("ringVolume", v).apply()
  fun setQuietMode(c: Context, v: String) = prefs(c).edit().putString("quietMode", v).apply()
  fun setQuality(c: Context, v: String) = prefs(c).edit().putString("quality", v).apply()
  fun setDropInsOn(c: Context, v: Boolean) = prefs(c).edit().putBoolean("dropInsOn", v).apply()
  fun setDropInRooms(c: Context, v: String) = prefs(c).edit().putString("dropInRooms", v).apply()
  fun setPhonesOn(c: Context, v: Boolean) = prefs(c).edit().putBoolean("phonesOn", v).apply()
  fun setGroupsOn(c: Context, v: Boolean) = prefs(c).edit().putBoolean("groupsOn", v).apply()
}
