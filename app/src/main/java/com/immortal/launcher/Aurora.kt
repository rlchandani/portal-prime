/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keyless "is there a chance of aurora *here* tonight" check for the home screen —
 * location-driven, works in any country and either hemisphere.
 *
 * The planetary K-index (Kp 0–9) measures global geomagnetic disturbance, but whether
 * *you* can see the aurora depends on how far the glowing oval has pushed toward the
 * equator versus how close you sit to the magnetic pole. So [status]:
 *   1. pulls the latest Kp + the next-24 h forecast peak from NOAA SWPC (keyless);
 *   2. converts the device's geographic lat/lon to **geomagnetic latitude** (dipole
 *      tilt — a Portal in Tromsø and one in Tasmania get judged correctly); and
 *   3. compares that to the Kp-driven equatorward edge of the auroral oval to decide
 *      whether there's no chance, a horizon-glow chance, or aurora likely overhead.
 *
 * The tile only "lights up" when there's actually something to go outside for.
 */
object Aurora {

  /** How good the odds are at the device's latitude right now / tonight. */
  enum class Chance {
    NONE,
    SLIM,
    POSSIBLE,
    LIKELY,
  }

  data class Status(
      val kpNow: Double,
      val kpForecast: Double, // peak Kp over the next ~24 h
      val geomagLat: Double, // device geomagnetic latitude (deg, signed)
      val chance: Chance,
      val headline: String,
      val detail: String,
      /** "N" or "S" — which horizon to face (poleward in the device's hemisphere). */
      val lookToward: String,
  )

  /**
   * Current aurora outlook for the device, or null if location/network is unavailable.
   * Touches the network only for the two SWPC feeds; all the latitude math is local.
   */
  // The tile fetches on every home-screen entry; cache the Kp pair briefly so we don't
  // hammer NOAA. Kp is a 3-hourly index — 15 minutes is plenty fresh.
  private const val KP_CACHE_MS = 15L * 60 * 1000
  @Volatile private var cachedKp: Pair<Double, Double>? = null
  @Volatile private var cachedKpAt = 0L

  fun status(context: Context): Status? {
    val (lat, lon) = Weather.coordinates(context) ?: return null
    val (kpNow, kpForecast) = kpPair() ?: return null
    return classify(lat, lon, kpNow, kpForecast)
  }

  /** (kpNow, kpForecastPeak) from cache or a fresh fetch. Null if it can't be fetched. */
  private fun kpPair(): Pair<Double, Double>? {
    val cached = cachedKp
    if (cached != null && System.currentTimeMillis() - cachedKpAt < KP_CACHE_MS) return cached
    val kpNow = fetchCurrentKp() ?: return cached // fall back to stale cache if offline
    val kpForecast = fetchForecastPeakKp().coerceAtLeast(kpNow)
    val pair = kpNow to kpForecast
    cachedKp = pair
    cachedKpAt = System.currentTimeMillis()
    return pair
  }

  // ---- The science (pure, unit-tested) -----------------------------------------

  private const val DEG = PI / 180.0

  // IGRF geomagnetic north pole (epoch ~2020): the dipole axis the oval hangs from.
  private const val POLE_LAT = 80.65
  private const val POLE_LON = -72.68

  /**
   * Lowest **geomagnetic** latitude at which the aurora typically reaches the horizon,
   * indexed by integer Kp 0..9. From the standard NOAA/space-weather viewing tables
   * (very nearly linear: ≈ 66.5 − 2.05·Kp).
   */
  private val KP_BOUNDARY =
      doubleArrayOf(66.5, 64.5, 62.4, 60.4, 58.3, 56.3, 54.2, 52.2, 50.1, 48.1)

  /** Interpolated equatorward oval edge (geomagnetic deg) for a fractional [kp]. */
  fun boundaryLat(kp: Double): Double {
    val k = kp.coerceIn(0.0, 9.0)
    val lo = k.toInt()
    if (lo >= 9) return KP_BOUNDARY[9]
    val frac = k - lo
    return KP_BOUNDARY[lo] + (KP_BOUNDARY[lo + 1] - KP_BOUNDARY[lo]) * frac
  }

  /** Geomagnetic latitude (signed deg) of a geographic point, via the tilted dipole. */
  fun geomagLatitude(latDeg: Double, lonDeg: Double): Double {
    val lat = latDeg * DEG
    val lon = lonDeg * DEG
    val pLat = POLE_LAT * DEG
    val pLon = POLE_LON * DEG
    val sinMag =
        sin(lat) * sin(pLat) + cos(lat) * cos(pLat) * cos(lon - pLon)
    return asin(sinMag.coerceIn(-1.0, 1.0)) / DEG
  }

  /** Decide the outlook from location + Kp. [kpView] (forecast-aware) drives the oval;
   * [kpNow] is reported for context. Public + side-effect-free for tests. */
  fun classify(latDeg: Double, lonDeg: Double, kpNow: Double, kpView: Double): Status {
    val geomag = geomagLatitude(latDeg, lonDeg)
    val absMag = abs(geomag)
    val boundary = boundaryLat(kpView)
    val margin = absMag - boundary // ≥0 means the oval has reached you
    val northern = geomag >= 0
    val look = if (northern) "N" else "S"
    val poleWord = if (northern) "northern" else "southern"

    // SLIM = the oval isn't reaching you now, but you're poleward of the Kp-9 limit
    // (48.1°), so a severe storm *could*. Below that limit, it's never visible here.
    val chance =
        when {
          margin >= -0.5 -> Chance.LIKELY
          margin >= -4.0 -> Chance.POSSIBLE
          absMag >= boundaryLat(9.0) -> Chance.SLIM
          else -> Chance.NONE
        }

    val kpStr = fmtKp(kpView)
    val headline =
        when (chance) {
          Chance.LIKELY -> "Aurora likely overhead tonight"
          Chance.POSSIBLE -> "Aurora possible low on the $poleWord horizon"
          Chance.SLIM -> "Slim chance — only in a strong storm"
          Chance.NONE -> "No aurora chance at your latitude"
        }
    val detail =
        when (chance) {
          Chance.NONE ->
              "Kp $kpStr. At ${fmt(absMag)}° geomagnetic you're too far from the pole — " +
                  "even a max Kp 9 storm only reaches ${fmt(boundaryLat(9.0))}°."
          Chance.SLIM ->
              "Kp $kpStr now — too low to reach you (you're at ${fmt(absMag)}° geomagnetic, " +
                  "oval edge ${fmt(boundary)}°). Only a major storm would bring it; worth watching."
          else ->
              "Kp $kpStr now, oval edge near ${fmt(boundary)}° geomagnetic; you're at " +
                  "${fmt(absMag)}°. Look toward the $poleWord horizon after dark, away from city lights."
        }
    return Status(
        kpNow = kpNow,
        kpForecast = kpView,
        geomagLat = geomag,
        chance = chance,
        headline = headline,
        detail = detail,
        lookToward = look,
    )
  }

  fun fmtKp(kp: Double): String =
      if (kp == kp.toInt().toDouble()) kp.toInt().toString()
      else String.format(Locale.US, "%.1f", kp)

  private fun fmt(d: Double): String = String.format(Locale.US, "%.0f", d)

  // ---- NOAA SWPC feeds (keyless) -----------------------------------------------

  /** Latest observed planetary Kp, or null on failure. */
  private fun fetchCurrentKp(): Double? =
      runCatching {
            val arr = JSONArray(httpGet("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json"))
            // The most recent reading is the last element. SWPC now serves an array of
            // objects ({"time_tag":…,"Kp":2.0,…}); older versions served an array of
            // arrays with a header row (["time_tag","Kp",…]). Handle both.
            if (arr.length() < 1) return null
            kpOf(arr.get(arr.length() - 1))
          }
          .getOrNull()

  /** Peak forecast Kp over the next ~24 h (0.0 if unavailable). */
  private fun fetchForecastPeakKp(): Double =
      runCatching {
            val arr = JSONArray(httpGet("https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json"))
            val now = System.currentTimeMillis()
            val horizon = now + 24L * 60 * 60 * 1000
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            var peak = 0.0
            for (i in 0 until arr.length()) {
              val row = arr.get(i)
              // Only future ("predicted") rows; observed/estimated ones are the past.
              if (kindOf(row) != "predicted") continue
              val t = runCatching { fmt.parse(timeOf(row))?.time }.getOrNull() ?: continue
              if (t in now..horizon) {
                val kp = kpOf(row) ?: continue
                if (kp > peak) peak = kp
              }
            }
            peak
          }
          .getOrDefault(0.0)

  // The SWPC feeds switched from array-of-arrays (with a header row) to array-of-objects;
  // these read a field from either shape so a future flip back doesn't break the tile.
  private fun kpOf(row: Any?): Double? =
      when (row) {
        is JSONObject -> row.optDouble("Kp", row.optDouble("kp", Double.NaN)).takeIf { !it.isNaN() }
        is JSONArray -> row.optString(1).toDoubleOrNull()
        else -> null
      }

  private fun timeOf(row: Any?): String =
      when (row) {
        is JSONObject -> row.optString("time_tag")
        is JSONArray -> row.optString(0)
        else -> ""
      }

  private fun kindOf(row: Any?): String =
      when (row) {
        is JSONObject -> row.optString("observed")
        is JSONArray -> row.optString(2)
        else -> ""
      }

  private fun httpGet(spec: String): String =
      Http.getString(spec, readTimeoutMs = 10000) ?: error("GET failed: $spec")
}
