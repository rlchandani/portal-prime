/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicLong
import java.util.Calendar
import java.util.SimpleTimeZone
import java.util.TimeZone
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * Keyless weather for the header and the photo frame.
 *
 * Robustness matters here: the free IP-geolocation services rate-limit hard
 * (ipapi.co returns HTTP 429 once its quota is hit), which previously blanked the
 * weather everywhere. So we:
 *   1. resolve the device's location ONCE and cache it (a fixed Portal doesn't
 *      move), so geolocation is hit at most once rather than on every refresh;
 *   2. try multiple geolocators (ipwho.is first — far more generous — then
 *      ipapi.co); and
 *   3. read the temperature from Open-Meteo, which is keyless and reliable.
 */
object Weather {

  private const val PREFS = "immortal_weather"
  private val solarLocationRevision = AtomicLong()
  private val solarDays = GoldenHourSolarCache()
  // Re-resolve the IP geolocation after this long, so a bad first fix (IP geo can land on the ISP's
  // datacenter city) or a relocated Portal self-corrects instead of being wrong forever.
  private const val LOCATION_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

  /** [feelsLike] is Open-Meteo's apparent temperature; null when the response omits it. */
  internal data class Conditions(val temp: Int, val code: Int, val feelsLike: Int? = null)
  // Independent header/card/frame readers share brief current conditions, never failures.
  private val currentConditions = SingleFlightValueCache<String, Conditions>(30_000L, 4)

  private fun conditions(context: Context): Conditions? {
    val (lat, lon) = location(context) ?: return null
    val url = currentUrl(lat, lon, ImmortalSettings.useFahrenheit(context))
    return currentConditions.get(url) { parseCurrent(httpGet(url)) }
  }

  internal fun currentUrl(lat: Double, lon: Double, fahrenheit: Boolean): String {
    val unit = if (fahrenheit) "fahrenheit" else "celsius"
    return "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
        "&current=temperature_2m,apparent_temperature,weather_code&temperature_unit=$unit"
  }

  internal fun parseCurrent(json: String): Conditions {
    val current = JSONObject(json).getJSONObject("current")
    val apparent = current.optDouble("apparent_temperature")
    return Conditions(current.getDouble("temperature_2m").roundToInt(), current.getInt("weather_code"),
        feelsLike = apparent.takeUnless { it.isNaN() }?.roundToInt())
  }

  /** Returns e.g. "☀️ 72°", or "" if it can't be fetched (caller can retry). */
  fun fetch(context: Context): String =
      runCatching {
            val current = conditions(context) ?: return ""
            "${emoji(current.code)} ${current.temp}°"
          }
          .getOrDefault("")

  /** Public accessor for the device's cached lat/lon (resolving + caching it once if
   * needed). Used by location-aware features like the ISS pass predictor. Null on
   * failure. */
  fun coordinates(context: Context): Pair<Double, Double>? = location(context)

  /** Cached lat/lon, or a fresh geolocation (cached on success). Also caches the city name. */
  private fun location(context: Context): Pair<Double, Double>? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // Use the cached fix only while it's fresh (within the TTL); a stale one falls through to a
    // re-resolve, but the fresh cache still short-circuits the common every-call case.
    val resolvedAt = prefs.getLong("resolved_at", 0L)
    val fresh = System.currentTimeMillis() - resolvedAt < LOCATION_TTL_MS
    if (fresh) {
      prefs.getString("lat", null)?.toDoubleOrNull()?.let { la ->
        prefs.getString("lon", null)?.toDoubleOrNull()?.let { lo -> return la to lo }
      }
    }
    for (url in listOf("https://ipwho.is/", "https://ipapi.co/json/")) {
      val coords =
          runCatching {
                val j = JSONObject(httpGet(url))
                val la = j.optDouble("latitude", Double.NaN)
                val lo = j.optDouble("longitude", Double.NaN)
                val city = j.optString("city", "")
                if (!la.isNaN() && !lo.isNaN() && (la != 0.0 || lo != 0.0)) Triple(la, lo, city)
                else null
              }
              .getOrNull()
      if (coords != null) {
        prefs
            .edit()
            .putString("lat", coords.first.toString())
            .putString("lon", coords.second.toString())
            .putString("city", coords.third)
            .putLong("resolved_at", System.currentTimeMillis())
            .apply()
        return coords.first to coords.second
      }
    }
    // Re-resolve failed (offline?). Fall back to a stale cached fix if we have one — a slightly
    // wrong/old location beats no weather at all until the network returns.
    prefs.getString("lat", null)?.toDoubleOrNull()?.let { la ->
      prefs.getString("lon", null)?.toDoubleOrNull()?.let { lo -> return la to lo }
    }
    return null
  }

  /** Clear the cached geolocation so the next call re-resolves (for a "re-detect location" action). */
  fun clearLocationCache(context: Context) {
    solarLocationRevision.incrementAndGet()
    solarDays.clear()
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().remove("lat").remove("lon").remove("city").remove("resolved_at").apply()
    currentConditions.clear()
  }

  /** Absolute solar instants, displayed using the weather response's location offset. */
  data class SunTimes(
      val sunriseMillis: Long,
      val sunsetMillis: Long,
      val utcOffsetSeconds: Int = TimeZone.getDefault().getOffset(sunriseMillis) / 1000,
  ) {
    private val locationZone = SimpleTimeZone(utcOffsetSeconds * 1000, "Weather location")

    fun formatted(use24Hour: Boolean): Pair<String, String> {
      val fmt = SimpleDateFormat(if (use24Hour) "HH:mm" else "h:mm a", Locale.getDefault())
          .apply { timeZone = locationZone }
      return fmt.format(java.util.Date(sunriseMillis)) to fmt.format(java.util.Date(sunsetMillis))
    }

    /** Sky phase and its solar boundaries must use the same location clock. */
    fun minuteOfDay(millis: Long): Int = Calendar.getInstance(locationZone).run {
      timeInMillis = millis
      get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
    }
  }

  /** Today's sunrise + sunset from Open-Meteo (keyless). Null on fetch/parse failure. */
  fun fetchSunTimes(context: Context): SunTimes? = runCatching {
    val (lat, lon) = location(context) ?: return null
    parseSunTimes(httpGet(sunTimesUrl(lat, lon)))
  }.getOrNull()

  /** Two solar days in one request: enough to select the next sunrise and sunset. */
  internal fun fetchSunDays(context: Context): List<SunTimes>? {
    val revision = solarLocationRevision.get()
    val result = solarDays.get(revision) {
      val (lat, lon) = location(context) ?: return@get null
      parseSunDays(httpGet(sunTimesUrl(lat, lon, days = 2)))
    }
    // A re-detect during lookup invalidates this location's result as well as its cached entry.
    return result.takeIf { revision == solarLocationRevision.get() }
  }

  internal fun sunTimesUrl(lat: Double, lon: Double, days: Int = 1): String {
    require(days in 1..2)
    return "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
        "&daily=sunrise,sunset&timezone=auto&forecast_days=$days"
  }

  internal fun parseSunTimes(json: String): SunTimes = parseSunDays(json).first()

  internal fun parseSunDays(json: String): List<SunTimes> {
    val root = JSONObject(json)
    val zone = responseZone(root)
    val daily = root.getJSONObject("daily")
    val sunrises = daily.getJSONArray("sunrise")
    val sunsets = daily.getJSONArray("sunset")
    require(sunrises.length() == sunsets.length()) { "Incomplete solar forecast" }
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        .apply { timeZone = zone; isLenient = false }
    return (0 until sunrises.length()).map { index ->
      val sunrise = iso.parse(sunrises.getString(index))!!.time
      val sunset = iso.parse(sunsets.getString(index))!!.time
      SunTimes(sunrise, sunset, zone.getOffset(sunrise) / 1000)
    }
  }

  /** Open-Meteo ISO timestamps use this fixed offset throughout a response, even over DST. */
  private fun responseZone(root: JSONObject): TimeZone {
    if (!root.has("utc_offset_seconds")) return TimeZone.getDefault()
    val offset = root.getInt("utc_offset_seconds")
    require(offset in -18 * 3600..18 * 3600) { "Invalid weather UTC offset" }
    return SimpleTimeZone(offset * 1000, "Weather location")
  }

  /** Last known city name (empty until geolocation has succeeded once). */
  fun cachedCity(context: Context): String =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("city", "") ?: ""

  /** Current conditions for the redesigned weather widget: city, rounded temp, and weather code.
   * [feelsLike] is the rounded apparent temperature, or null when unknown. */
  data class Current(val city: String, val temp: Int, val code: Int, val feelsLike: Int? = null)

  fun fetchCurrent(context: Context): Current? =
      runCatching {
            val current = conditions(context) ?: return null
            Current(city = cachedCity(context), temp = current.temp, code = current.code,
                feelsLike = current.feelsLike)
          }
          .getOrNull()

  /** One day of the multi-day forecast. [label] is "Today" then "Mon", "Tue", … */
  data class DayForecast(val label: String, val code: Int, val hi: Int, val lo: Int)

  /** One hour of the hourly forecast. [label] is "Now" then "1 PM", "2 PM", … */
  data class HourForecast(
      val label: String,
      val code: Int,
      val temp: Int,
      internal val instantMillis: Long? = null,
      internal val utcOffsetSeconds: Int = 0,
  )

  /** A combined forecast: both views are fetched in one call so the home-screen
   * widget can switch between hourly and 7-day without re-hitting the network. */
  data class Forecast(val days: List<DayForecast>, val hours: List<HourForecast>) {
    /** Presentation of retained hourly data; does not fetch or replace the forecast. */
    fun hourLabels(use24Hour: Boolean): List<String> {
      val format = SimpleDateFormat(if (use24Hour) "HH" else "h a", Locale.getDefault())
      var offset: Int? = null
      return hours.map { hour ->
        if (hour.label == "Now" || hour.instantMillis == null) hour.label
        else {
          if (offset != hour.utcOffsetSeconds) {
            offset = hour.utcOffsetSeconds
            format.timeZone = SimpleTimeZone(hour.utcOffsetSeconds * 1000, "Weather location")
          }
          format.format(java.util.Date(hour.instantMillis))
        }
      }
    }
  }

  /**
   * Fetches a 7-day daily forecast and the next ~12 hours of hourly forecast from
   * Open-Meteo (keyless), for the home-screen weather widget. Returns null if it
   * can't be fetched (caller can retry). `timezone=auto` makes Open-Meteo return
   * local times, which we parse using the response's fixed UTC offset.
   */
  fun fetchForecast(context: Context): Forecast? =
      runCatching {
            val (lat, lon) = location(context) ?: return null
            // °F or °C per the user's setting (default: follow the device locale).
            val json = httpGet(forecastUrl(lat, lon, ImmortalSettings.useFahrenheit(context)))
            parseForecast(json, System.currentTimeMillis(), ImmortalSettings.use24HourClock(context))
          }
          .getOrNull()

  /** The Open-Meteo request for [fetchForecast]; split out so the unit choice can be
   * verified in tests. `timezone=auto` makes the response use the location's local
   * times, which [parseForecast] reads using the response's fixed UTC offset. */
  internal fun forecastUrl(lat: Double, lon: Double, fahrenheit: Boolean): String {
    val unit = if (fahrenheit) "fahrenheit" else "celsius"
    return "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
        "&hourly=weather_code,temperature_2m" +
        "&forecast_days=7&temperature_unit=$unit&timezone=auto"
  }

  /** Pure parse of an Open-Meteo forecast response. [nowMillis] is the reference "now"
   * used to drop already-past hours, passed in (rather than read from the clock) so the
   * hourly logic is deterministic under test. */
  internal fun parseForecast(json: String, nowMillis: Long, use24Hour: Boolean = false): Forecast {
    val root = JSONObject(json)
    return Forecast(days = parseDays(root), hours = parseHours(root, nowMillis, use24Hour))
  }

  private fun parseDays(root: JSONObject): List<DayForecast> {
    val d = root.getJSONObject("daily")
    val time = d.getJSONArray("time")
    val code = d.getJSONArray("weather_code")
    val hi = d.getJSONArray("temperature_2m_max")
    val lo = d.getJSONArray("temperature_2m_min")
    val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
    val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return (0 until time.length()).map { i ->
      val label =
          if (i == 0) "Today"
          else runCatching { dayFmt.format(isoDate.parse(time.getString(i))!!) }.getOrDefault("")
      DayForecast(
          label = label,
          code = code.getInt(i),
          hi = hi.getDouble(i).roundToInt(),
          lo = lo.getDouble(i).roundToInt())
    }
  }

  private fun parseHours(
      root: JSONObject,
      nowMillis: Long,
      use24Hour: Boolean = false
  ): List<HourForecast> {
    val h = root.getJSONObject("hourly")
    val time = h.getJSONArray("time")
    val code = h.getJSONArray("weather_code")
    val temp = h.getJSONArray("temperature_2m")
    val zone = responseZone(root)
    val isoHour = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        .apply { timeZone = zone; isLenient = false }
    // 24-hour: a bare hour ("13", "00"); 12-hour: "1 PM".
    val hourFmt = SimpleDateFormat(if (use24Hour) "HH" else "h a", Locale.getDefault())
        .apply { timeZone = zone }
    // Include the current location hour, but never label the previous hour "Now".
    val cutoff = Calendar.getInstance(zone).apply {
      timeInMillis = nowMillis
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val out = ArrayList<HourForecast>(12)
    var first = true
    for (i in 0 until time.length()) {
      val t = runCatching { isoHour.parse(time.getString(i))!!.time }.getOrNull() ?: continue
      if (t < cutoff) continue
      out.add(
          HourForecast(
              label = if (first) "Now" else hourFmt.format(java.util.Date(t)),
              code = code.getInt(i),
              temp = temp.getDouble(i).roundToInt(),
              instantMillis = t,
              utcOffsetSeconds = zone.getOffset(t) / 1000))
      first = false
      if (out.size >= 12) break
    }
    return out
  }

  private fun httpGet(spec: String): String =
      Http.getString(spec, readTimeoutMs = 8000) ?: error("GET failed: $spec")

  fun emoji(code: Int): String =
      when (code) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        3 -> "☁️"
        in 45..48 -> "🌫️"
        in 51..67 -> "🌦️"
        in 71..77 -> "🌨️"
        in 80..82 -> "🌧️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
      }
}
