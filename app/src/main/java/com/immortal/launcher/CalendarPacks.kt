/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.util.Calendar

/**
 * Installable calendar packs — the name-day idea, broadened. A household switches on the pack
 * that fits it: Romanian name-days + Orthodox feasts, Irish bank holidays + saints, or daily
 * Islamic prayer times. Each enabled pack contributes one or more lines to the ambient screensaver
 * dashboard (and any future header); they're all keyless and computed on-device.
 *
 * All packs store their on/off in a single [PREFS]; [AVAILABLE] drives the settings toggles and
 * [headerLines] the display, so adding a pack is one entry in each.
 */
object CalendarPacks {

  private const val PREFS = "immortal_packs"

  const val ROMANIAN = "romanian"
  const val IRISH = "irish"
  const val PRAYER = "prayer"

  data class Pack(val id: String, val title: String, val blurb: String)

  /** Packs the user can switch on, in display order. */
  val AVAILABLE =
      listOf(
          Pack(
              ROMANIAN,
              "Romanian name-days & Orthodox feasts",
              "Today's name-days and the Orthodox feast calendar"),
          Pack(IRISH, "Irish holidays", "Bank holidays + saints' days (St Patrick's, St Brigid's…)"),
          Pack(PRAYER, "Prayer times", "Daily Islamic prayer times for your location"),
      )

  fun isEnabled(context: Context, id: String): Boolean =
      prefs(context).getBoolean(id, false)

  fun setEnabled(context: Context, id: String, on: Boolean) {
    prefs(context).edit().putBoolean(id, on).apply()
  }

  /** Header lines contributed by the enabled add-on packs, in display order. */
  fun headerLines(context: Context, now: Calendar = Calendar.getInstance()): List<String> {
    val out = ArrayList<String>(4)
    if (isEnabled(context, ROMANIAN)) {
      val feast = FeastDays.forToday(now)
      if (feast.isNotEmpty()) out.add("🕯️ $feast")
      val names = NameDays.todayLabel(now)
      if (names.isNotEmpty()) out.add("🎉 $names")
    }
    if (isEnabled(context, IRISH)) {
      val h = IrishHolidays.forToday(now)
      if (h.isNotEmpty()) out.add("🍀 $h")
    }
    if (isEnabled(context, PRAYER)) {
      val line = PrayerTimes.nextLine(context, now)
      if (line.isNotEmpty()) out.add(line)
    }
    return out
  }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
