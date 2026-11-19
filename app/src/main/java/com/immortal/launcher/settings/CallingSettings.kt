/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import android.content.Context
import com.immortal.launcher.CallQuality
import com.immortal.launcher.CallQuietMode
import com.immortal.launcher.CallRingtone
import com.immortal.launcher.CallWhoCanCall
import com.immortal.launcher.Calling
import com.immortal.launcher.CallingConfig
import com.immortal.launcher.CallingConfig.Settings
import com.immortal.launcher.DropInGrid
import org.json.JSONObject

/**
 * Settings › Calls ([CallingConfig]). Privacy guarantees are not settings: they render as
 * locked "Always" rows in the page's own block, so there is nothing here that could turn them
 * off. The drop-in grid writes this Portal's column through [set] like every other row.
 */
internal object CallingSettings {
  val domain: SettingsDomain<Settings> =
      SettingsDomain(
          id = "calling",
          title = "Calls",
          load = CallingConfig::load,
          specs = listOf(
              EnumSpec("whoCanCall", "Who can call this Portal", get = { it.whoCanCall }, set = CallingConfig::setWhoCanCall,
                  options = CallWhoCanCall.entries.map { it.wire to it.label },
                  coerce = { v -> CallWhoCanCall.entries.firstOrNull { it.wire == v }?.wire },
                  help = "Only Portals that share this home’s household key can find or ring it."),
              EnumSpec("ringtone", "Ringtone", get = { it.ringtone }, set = CallingConfig::setRingtone,
                  options = CallRingtone.entries.map { it.wire to it.label },
                  coerce = { v -> CallRingtone.entries.firstOrNull { it.wire == v }?.wire }),
              IntSpec("ringVolume", "Ring volume", get = { it.ringVolume }, set = CallingConfig::setRingVolume,
                  min = CallingConfig.MIN_RING_VOLUME, max = CallingConfig.MAX_RING_VOLUME,
                  step = CallingConfig.RING_VOLUME_STEP, format = { "$it%" },
                  help = "Rings for 45 seconds. Never rings in quiet hours."),
              EnumSpec("quietMode", "During quiet hours", get = { it.quietMode }, set = CallingConfig::setQuietMode,
                  options = CallQuietMode.entries.map { it.wire to it.label },
                  coerce = { v -> CallQuietMode.entries.firstOrNull { it.wire == v }?.wire },
                  help = "Callers are told this room is in quiet hours. Missed calls wait in Recent."),
              EnumSpec("quality", "Call quality", get = { it.quality }, set = CallingConfig::setQuality,
                  options = CallQuality.entries.map { it.wire to it.label },
                  coerce = { v -> CallQuality.entries.firstOrNull { it.wire == v }?.wire },
                  help = "Auto sends 720p and steps down by itself when the Portal is busy or low on memory."),
              BoolSpec("dropInsOn", "Drop-ins on this Portal", get = { it.dropInsOn }, set = CallingConfig::setDropInsOn,
                  help = "Off until you turn it on. Rooms then ask first unless you choose Chime, join below."),
              BoolSpec("phonesOn", "Phones can call this Portal", get = { it.phonesOn }, set = CallingConfig::setPhonesOn,
                  help = "Paired phones with Portal Toolkit, on the home Wi-Fi. Phones call, they never drop in."),
              StringSpec("dropInRooms", "Drop-ins room by room", get = { it.dropInRooms }, set = CallingConfig::setDropInRooms,
                  applyWhen = { DropInGrid.decode(it) != null },
                  visible = { _, s -> s.dropInsOn }),
          ),
          defaults = { Settings() },
          onApplied = { context, _ -> Calling.settingsChanged(context) },
      )

  /** Writes one setting through the registry (validated, then the runtime re-announces itself). */
  fun set(context: Context, key: String, value: Any): Boolean =
      domain.apply(context, JSONObject().put(key, value)).isNotEmpty()
}
