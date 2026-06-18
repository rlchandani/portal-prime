/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.text.format.DateFormat
import java.util.Locale

/**
 * Immortal's own user preferences (as opposed to the screensaver's, which live in
 * [ScreensaverConfig]). Reached from the "Immortal" tile in the launcher's
 * Settings folder. Everything here defaults to the pre-1.25 behaviour so existing
 * installs are unaffected until the user changes something.
 */
object ImmortalSettings {

  private const val PREFS = "immortal_settings"

  // Weather temperature unit.
  const val UNIT_AUTO = "auto" // follow the device locale
  const val UNIT_F = "f"
  const val UNIT_C = "c"

  // Home-grid tile size.
  const val SIZE_STANDARD = "standard" // 6 columns, 88dp tiles (the original look)
  const val SIZE_LARGE = "large" // 5 columns, 110dp tiles (closer to the stock launcher)
  const val SIZE_XL = "xl" // 4 columns, 140dp tiles (for the big-screen Portal+)

  // Optional home-screen weather forecast widget, shown below the app grid.
  const val WIDGET_OFF = "off" // no forecast (default)
  const val WIDGET_HOURLY = "hourly" // hour-by-hour for the next several hours
  const val WIDGET_DAILY = "daily" // a high/low for each of the next 7 days

  // Clock format for the launcher header, screensaver, and hourly forecast labels.
  const val CLOCK_AUTO = "auto" // follow the device's 24-hour system setting (default)
  const val CLOCK_12 = "12" // force 12-hour (e.g. 1:05, 1 PM)
  const val CLOCK_24 = "24" // force 24-hour (e.g. 13:05, 13)

  data class Settings(
      val weatherUnit: String = UNIT_AUTO,
      val tileSize: String = SIZE_STANDARD,
      val weatherWidget: String = WIDGET_OFF,
      val clockFormat: String = CLOCK_AUTO,
      // Mini-player in the home header (cover art + controls), shown only while
      // something is actually playing. Defaults on — useful to everyone, unobtrusive.
      val showMiniPlayer: Boolean = true,
      // Multi-room audio: when this Portal is a Snapcast speaker, surface what the
      // group is playing on the now-playing card (read from the Music Assistant /
      // snapserver at [snapcastHost]). Off until configured.
      val multiRoomEnabled: Boolean = false,
      val snapcastHost: String = "",
      // Music Assistant login — needed only to send transport (play/pause/next) to MA's
      // authenticated API; the now-playing metadata itself needs no credentials.
      val maUsername: String = "",
      val maPassword: String = "",
  )

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(context: Context): Settings {
    val p = prefs(context)
    return Settings(
        weatherUnit = p.getString("weather_unit", UNIT_AUTO) ?: UNIT_AUTO,
        tileSize = p.getString("tile_size", SIZE_STANDARD) ?: SIZE_STANDARD,
        weatherWidget = p.getString("weather_widget", WIDGET_OFF) ?: WIDGET_OFF,
        clockFormat = p.getString("clock_format", CLOCK_AUTO) ?: CLOCK_AUTO,
        showMiniPlayer = p.getBoolean("show_mini_player", true),
        multiRoomEnabled = p.getBoolean("multiroom_enabled", false),
        snapcastHost = p.getString("snapcast_host", "") ?: "",
        maUsername = p.getString("ma_username", "") ?: "",
        maPassword = p.getString("ma_password", "") ?: "",
    )
  }

  fun multiRoomEnabled(c: Context): Boolean = prefs(c).getBoolean("multiroom_enabled", false)

  fun snapcastHost(c: Context): String = prefs(c).getString("snapcast_host", "")?.trim() ?: ""

  fun maUser(c: Context): String = prefs(c).getString("ma_username", "")?.trim() ?: ""

  fun maPass(c: Context): String = prefs(c).getString("ma_password", "") ?: ""

  fun setMultiRoomEnabled(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("multiroom_enabled", on).apply()

  fun setSnapcastHost(c: Context, host: String) =
      prefs(c).edit().putString("snapcast_host", host.trim()).apply()

  fun setMaUsername(c: Context, v: String) =
      prefs(c).edit().putString("ma_username", v.trim()).apply()

  fun setMaPassword(c: Context, v: String) = prefs(c).edit().putString("ma_password", v).apply()

  fun setWeatherUnit(c: Context, unit: String) =
      prefs(c).edit().putString("weather_unit", unit).apply()

  fun setTileSize(c: Context, size: String) = prefs(c).edit().putString("tile_size", size).apply()

  fun setWeatherWidget(c: Context, mode: String) =
      prefs(c).edit().putString("weather_widget", mode).apply()

  fun setClockFormat(c: Context, fmt: String) =
      prefs(c).edit().putString("clock_format", fmt).apply()

  fun setShowMiniPlayer(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("show_mini_player", on).apply()

  /**
   * Whether the clock should render in 24-hour form. AUTO follows the device's
   * system 24-hour setting; 12/24 force it. Reads [DateFormat.is24HourFormat] for
   * the AUTO case; see [resolve24Hour] for the pure, testable core.
   */
  fun use24HourClock(context: Context): Boolean =
      resolve24Hour(load(context).clockFormat, DateFormat.is24HourFormat(context))

  /** Pure resolution of the clock preference against the system setting. */
  fun resolve24Hour(clockFormat: String, systemIs24Hour: Boolean): Boolean =
      when (clockFormat) {
        CLOCK_24 -> true
        CLOCK_12 -> false
        else -> systemIs24Hour
      }

  /** Resolved unit for a fetch: true → Fahrenheit, false → Celsius. */
  fun useFahrenheit(context: Context): Boolean =
      when (load(context).weatherUnit) {
        UNIT_F -> true
        UNIT_C -> false
        else -> localeUsesFahrenheit()
      }

  /**
   * The handful of territories that use Fahrenheit day-to-day; everywhere else
   * gets Celsius. Pure + injectable for unit tests.
   */
  fun localeUsesFahrenheit(locale: Locale = Locale.getDefault()): Boolean =
      locale.country.uppercase(Locale.ROOT) in FAHRENHEIT_COUNTRIES

  private val FAHRENHEIT_COUNTRIES =
      setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH", "PR", "GU", "VI", "AS")
}
