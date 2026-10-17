/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONObject

/**
 * Keyless "when does the ISS fly over you" predictor for the home screen.
 *
 * Everything past the one-off TLE download runs offline on-device: a self-contained
 * near-Earth **SGP4** propagator ([Sat]) turns the two-line element set into a sky
 * position minute-by-minute, and [predict] scans the next ~36 h for the times the
 * station climbs above the horizon — start/peak/end, peak elevation, compass
 * direction, and whether it'll actually be *visible* to the naked eye (station
 * sunlit while the sky here is dark).
 *
 * Why SGP4 and not an API: the old keyless "ISS pass" endpoints (open-notify) are
 * dead, and the survivors are key-walled. The orbital model is the standard
 * NORAD/Vallado one (validated against the canonical test vector in
 * `IssPassesTest`), so a single keyless TLE fetch — Celestrak first, wheretheiss.at
 * as fallback — is all the network this needs, and a cached TLE keeps working for
 * days without it.
 */
object IssPasses {

  private const val PREFS = "immortal_iss"
  private const val CATNR = 25544 // ISS (ZARYA)

  // A TLE drifts slowly; refreshing a couple of times a day is plenty and keeps a
  // mostly-offline Portal honest without hammering the source.
  private const val TLE_FRESH_MS = 12L * 60 * 60 * 1000

  /** A single above-horizon pass. Times are epoch millis (device-local zone on
   * display). [maxElevationDeg] is the peak height; [visible] means naked-eye. */
  data class Pass(
      val startMillis: Long,
      val peakMillis: Long,
      val endMillis: Long,
      val maxElevationDeg: Int,
      val startDir: String,
      val peakDir: String,
      val endDir: String,
      val visible: Boolean,
  )

  /**
   * Upcoming passes over the device, soonest first (empty on any failure / no fix).
   * Network is only touched to (re)fetch the TLE; the search itself is local.
   *
   * @param max how many passes to return.
   * @param horizonDeg ignore grazing passes whose peak is below this (10° default —
   *   anything lower is lost behind buildings/trees from an indoor Portal anyway).
   */
  enum class PredictionProblem { LOCATION, ORBIT, CALCULATION }

  /** A successful empty scan is distinct from unavailable inputs or calculation failure. */
  data class Prediction(val passes: List<Pass> = emptyList(), val problem: PredictionProblem? = null)

  /** Compatibility list boundary used by the existing compact Home summary. */
  fun predict(context: Context, max: Int = 5, horizonDeg: Double = 10.0): List<Pass> =
      predictStatus(context, max, horizonDeg).passes

  fun predictStatus(context: Context, max: Int = 5, horizonDeg: Double = 10.0): Prediction {
    ToolsWork.checkpoint()
    val coords = Weather.coordinates(context)
    ToolsWork.checkpoint()
    val (lat, lon) = coords ?: return Prediction(problem = PredictionProblem.LOCATION)
    val sat = loadSat(context)
    ToolsWork.checkpoint()
    if (sat == null) return Prediction(problem = PredictionProblem.ORBIT)
    val passes = runCatching { scan(sat, lat, lon, max, horizonDeg) }
    ToolsWork.checkpoint()
    return if (passes.isSuccess) Prediction(passes.getOrThrow())
        else Prediction(problem = PredictionProblem.CALCULATION)
  }

  /** The very next pass (or null), for the compact home tile subtitle. */
  fun next(context: Context): Pass? = predict(context, max = 1).firstOrNull()

  // ---- Pass search -------------------------------------------------------------

  private const val STEP_S = 30.0 // sky-position sampling step
  private const val SPAN_H = 36 // how far ahead to look

  private fun scan(sat: Sat, latDeg: Double, lonDeg: Double, max: Int, horizonDeg: Double): List<Pass> {
    val obs = observerEcef(latDeg, lonDeg)
    val latRad = latDeg * DEG
    val lonRad = lonDeg * DEG
    val nowMs = System.currentTimeMillis()
    val steps = (SPAN_H * 3600 / STEP_S).toInt()

    val passes = ArrayList<Pass>()
    var usableSamples = 0
    var inPass = false
    var startMs = 0L
    var startAz = 0.0
    var peakEl = -90.0
    var peakAz = 0.0
    var peakMs = 0L
    var anyVisible = false
    var prevAz = 0.0
    var prevMs = 0L

    for (i in 0..steps) {
      ToolsWork.checkpoint()
      val tMs = nowMs + (i * STEP_S * 1000).toLong()
      val look = lookAngle(sat, tMs, obs, latRad, lonRad) ?: continue
      if (!look.first.isFinite() || !look.second.isFinite()) continue
      usableSamples++
      val elDeg = look.first / DEG
      val azDeg = look.second / DEG
      if (elDeg >= 0.0) {
        if (!inPass) {
          inPass = true
          startMs = tMs
          startAz = azDeg
          peakEl = elDeg
          peakAz = azDeg
          peakMs = tMs
          anyVisible = false
        }
        if (elDeg > peakEl) {
          peakEl = elDeg
          peakAz = azDeg
          peakMs = tMs
        }
        if (!anyVisible && isVisible(sat, tMs, obs, latRad, lonRad, elDeg)) anyVisible = true
        prevAz = azDeg
        prevMs = tMs
      } else if (inPass) {
        inPass = false
        if (peakEl >= horizonDeg) {
          passes.add(
              Pass(
                  startMillis = startMs,
                  peakMillis = peakMs,
                  endMillis = prevMs,
                  maxElevationDeg = peakEl.toInt(),
                  startDir = compass(startAz),
                  peakDir = compass(peakAz),
                  endDir = compass(prevAz),
                  visible = anyVisible,
              ))
          if (passes.size >= max) return passes
        }
      }
    }
    check(usableSamples > 0) { "No usable orbital samples" }
    return passes
  }

  /** Topocentric look angle (elevationRad, azimuthRad) of the satellite, or null if
   * the propagator decayed/failed at this instant. */
  private fun lookAngle(
      sat: Sat,
      tMs: Long,
      obs: DoubleArray,
      latRad: Double,
      lonRad: Double,
  ): Pair<Double, Double>? {
    val teme = sat.propagate(minutesSinceEpoch(sat, tMs)) ?: return null
    val ecef = temeToEcef(teme, julianDate(tMs))
    return topocentric(ecef, obs, latRad, lonRad)
  }

  /** Naked-eye check at [elDeg]: the station must be sunlit while the observer's sky
   * is dark (sun below the −6° civil-twilight line). */
  private fun isVisible(
      sat: Sat,
      tMs: Long,
      obs: DoubleArray,
      latRad: Double,
      lonRad: Double,
      elDeg: Double,
  ): Boolean {
    if (elDeg < 0) return false
    val jd = julianDate(tMs)
    val sunEci = sunEci(jd)
    val sunEcef = temeToEcef(sunEci, jd) // sun direction is ~unchanged by the GMST spin error
    val sunEl = topocentric(sunEcef, obs, latRad, lonRad).first / DEG
    if (sunEl > -6.0) return false // sky too bright
    val teme = sat.propagate(minutesSinceEpoch(sat, tMs)) ?: return false
    return satSunlit(teme, sunEci)
  }

  /** True if the station is out of Earth's cylindrical shadow (lit by the sun). */
  private fun satSunlit(satKm: DoubleArray, sunEci: DoubleArray): Boolean {
    val sunMag = mag(sunEci)
    val sx = sunEci[0] / sunMag
    val sy = sunEci[1] / sunMag
    val sz = sunEci[2] / sunMag
    val proj = satKm[0] * sx + satKm[1] * sy + satKm[2] * sz
    if (proj > 0) return true // sun-facing side: always lit
    val satMag2 = satKm[0] * satKm[0] + satKm[1] * satKm[1] + satKm[2] * satKm[2]
    val perp = sqrt((satMag2 - proj * proj).coerceAtLeast(0.0))
    return perp > RADIUS_EARTH_KM // misses the umbra cylinder
  }

  // ---- TLE fetch + cache -------------------------------------------------------

  /** Build a propagator from the cached TLE, refreshing over the network if stale /
   * absent. Null if we have no TLE and can't fetch one. */
  private fun loadSat(context: Context): Sat? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val age = System.currentTimeMillis() - prefs.getLong("tle_time", 0L)
    var l1 = prefs.getString("tle1", null)
    var l2 = prefs.getString("tle2", null)
    if (l1 == null || l2 == null || age > TLE_FRESH_MS) {
      val fetched = fetchTle()
      ToolsWork.checkpoint()
      if (fetched != null) {
        l1 = fetched.first
        l2 = fetched.second
        prefs
            .edit()
            .putString("tle1", l1)
            .putString("tle2", l2)
            .putLong("tle_time", System.currentTimeMillis())
            .apply()
      }
    }
    if (l1 == null || l2 == null) return null
    return runCatching { Sat.fromTle(l1, l2) }.getOrNull()
  }

  /** Keyless ISS TLE — Celestrak first (canonical), wheretheiss.at as fallback. */
  private fun fetchTle(): Pair<String, String>? {
    runCatching {
      val body =
          httpGet("https://celestrak.org/NORAD/elements/gp.php?CATNR=$CATNR&FORMAT=tle")
      val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
      val l1 = lines.firstOrNull { it.startsWith("1 ") }
      val l2 = lines.firstOrNull { it.startsWith("2 ") }
      if (l1 != null && l2 != null) return l1 to l2
    }
    ToolsWork.checkpoint()
    runCatching {
      val j = JSONObject(httpGet("https://api.wheretheiss.at/v1/satellites/$CATNR/tles"))
      val l1 = j.optString("line1").trim()
      val l2 = j.optString("line2").trim()
      if (l1.startsWith("1 ") && l2.startsWith("2 ")) return l1 to l2
    }
    ToolsWork.checkpoint()
    return null
  }

  private fun httpGet(spec: String): String {
    ToolsWork.checkpoint()
    val c = URL(spec).openConnection() as HttpURLConnection
    try {
      ToolsWork.track(c)
      c.connectTimeout = 8000
      c.readTimeout = 10000
      c.setRequestProperty("User-Agent", "Immortal/1.0")
      ToolsWork.checkpoint()
      val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
      ToolsWork.checkpoint()
      return body
    } finally {
      runCatching { ToolsWork.disconnect(c) }
    }
  }

  // ---- Geometry helpers --------------------------------------------------------

  const val RADIUS_EARTH_KM = 6378.135
  private const val DEG = PI / 180.0
  private const val TWO_PI = 2.0 * PI
  private val E2 = 0.006694385 // WGS-72 first eccentricity squared

  /** Minutes between the TLE epoch and [tMs]. */
  private fun minutesSinceEpoch(sat: Sat, tMs: Long): Double =
      (julianDate(tMs) - sat.jdEpoch) * 1440.0

  /** Julian date (UT1≈UTC) from epoch millis. */
  private fun julianDate(tMs: Long): Double = 2440587.5 + tMs / 86_400_000.0

  /** Observer ECEF position (km) at sea level on the WGS-72 ellipsoid. */
  private fun observerEcef(latDeg: Double, lonDeg: Double): DoubleArray {
    val lat = latDeg * DEG
    val lon = lonDeg * DEG
    val sinLat = sin(lat)
    val n = RADIUS_EARTH_KM / sqrt(1.0 - E2 * sinLat * sinLat)
    val x = n * cos(lat) * cos(lon)
    val y = n * cos(lat) * sin(lon)
    val z = (n * (1.0 - E2)) * sinLat
    return doubleArrayOf(x, y, z)
  }

  /** Rotate a TEME (ECI) vector into Earth-fixed (ECEF) coords using GMST. Ignores
   * polar motion / nutation — negligible for pointing a tile at the sky. */
  private fun temeToEcef(eci: DoubleArray, jd: Double): DoubleArray {
    val g = gmst(jd)
    val c = cos(g)
    val s = sin(g)
    return doubleArrayOf(
        c * eci[0] + s * eci[1],
        -s * eci[0] + c * eci[1],
        eci[2],
    )
  }

  /** (elevationRad, azimuthRad-from-north) of an ECEF target from the observer. */
  private fun topocentric(
      target: DoubleArray,
      obs: DoubleArray,
      latRad: Double,
      lonRad: Double,
  ): Pair<Double, Double> {
    val rx = target[0] - obs[0]
    val ry = target[1] - obs[1]
    val rz = target[2] - obs[2]
    val sinLat = sin(latRad)
    val cosLat = cos(latRad)
    val sinLon = sin(lonRad)
    val cosLon = cos(lonRad)
    val south = sinLat * cosLon * rx + sinLat * sinLon * ry - cosLat * rz
    val east = -sinLon * rx + cosLon * ry
    val zen = cosLat * cosLon * rx + cosLat * sinLon * ry + sinLat * rz
    val range = sqrt(rx * rx + ry * ry + rz * rz)
    val el = asin((zen / range).coerceIn(-1.0, 1.0))
    var az = atan2(east, -south)
    if (az < 0) az += TWO_PI
    return el to az
  }

  /** Greenwich Mean Sidereal Time (rad) — the standard SGP4 `gstime`. */
  private fun gmst(jd: Double): Double {
    val t = (jd - 2451545.0) / 36525.0
    var g =
        -6.2e-6 * t * t * t +
            0.093104 * t * t +
            (876600.0 * 3600 + 8640184.812866) * t +
            67310.54841
    g = (g * DEG / 240.0) % TWO_PI // seconds of time -> rad
    if (g < 0) g += TWO_PI
    return g
  }

  /** Low-precision geocentric sun position (km, ECI) — enough to tell day from night
   * and lit from shadowed. (Astronomical Almanac, ~0.01° accuracy.) */
  private fun sunEci(jd: Double): DoubleArray {
    val n = jd - 2451545.0
    val l = (280.460 + 0.9856474 * n) * DEG
    val g = (357.528 + 0.9856003 * n) * DEG
    val lambda = l + (1.915 * sin(g) + 0.020 * sin(2 * g)) * DEG
    val eps = (23.439 - 0.0000004 * n) * DEG
    val au = 149_597_870.7
    return doubleArrayOf(
        au * cos(lambda),
        au * cos(eps) * sin(lambda),
        au * sin(eps) * sin(lambda),
    )
  }

  private fun mag(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

  private val DIRS =
      arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

  /** Azimuth (deg from north) -> 16-point compass label. */
  fun compass(azDeg: Double): String {
    var a = azDeg % 360.0
    if (a < 0) a += 360.0
    return DIRS[(((a + 11.25) / 22.5).toInt()) % 16]
  }

  /** Format a pass time as the device-local "h:mm a" / weekday for the UI. */
  fun timeLabel(millis: Long): String =
      SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(millis))

  // ---- SGP4 (near-Earth) -------------------------------------------------------

  /**
   * A near-Earth SGP4 propagator built from a TLE. Deep-space (period ≥ 225 min) is
   * intentionally omitted — the ISS, like all LEO targets, is firmly near-Earth.
   * [propagate] returns the TEME position in km at `tsinceMin` minutes from epoch
   * (null if the orbit decayed). Constants are WGS-72, matching the model the TLEs
   * are generated against.
   */
  class Sat private constructor(
      val jdEpoch: Double,
      private val ecco: Double,
      private val inclo: Double,
      private val nodeo: Double,
      private val argpo: Double,
      private val mo: Double,
      private val no: Double, // mean motion, rad/min (Kozai-recovered)
      private val ao: Double,
      private val con41: Double,
      private val x1mth2: Double,
      private val x7thm1: Double,
      private val xlcof: Double,
      private val aycof: Double,
      private val cc1: Double,
      private val cc4: Double,
      private val cc5: Double,
      private val t2cof: Double,
      private val mdot: Double,
      private val argpdot: Double,
      private val nodedot: Double,
      private val nodecf: Double,
      private val omgcof: Double,
      private val xmcof: Double,
      private val eta: Double,
      private val sinmao: Double,
      private val delmo: Double,
      private val bstar: Double,
      private val isimp: Boolean,
      private val d2: Double,
      private val d3: Double,
      private val d4: Double,
      private val t3cof: Double,
      private val t4cof: Double,
      private val t5cof: Double,
  ) {

    /** TEME position (km) at [tsinceMin] minutes past epoch; null on decay. */
    fun propagate(tsinceMin: Double): DoubleArray? {
      val xmdf = mo + mdot * tsinceMin
      val argpdf = argpo + argpdot * tsinceMin
      val nodedf = nodeo + nodedot * tsinceMin
      var argpm = argpdf
      var mm = xmdf
      val t2 = tsinceMin * tsinceMin
      val nodem = nodedf + nodecf * t2
      var tempa = 1.0 - cc1 * tsinceMin
      var tempe = bstar * cc4 * tsinceMin
      var templ = t2cof * t2
      if (!isimp) {
        val delomg = omgcof * tsinceMin
        val delmtemp = 1.0 + eta * cos(xmdf)
        val delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo)
        val temp = delomg + delm
        mm = xmdf + temp
        argpm = argpdf - temp
        val t3 = t2 * tsinceMin
        val t4 = t3 * tsinceMin
        tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
        tempe += bstar * cc5 * (sin(mm) - sinmao)
        templ += t3cof * t3 + t4 * (t4cof + tsinceMin * t5cof)
      }

      val am = ao * tempa * tempa
      val nm = XKE / am.pow(1.5)
      var em = ecco - tempe
      if (em >= 1.0 || em < -0.001) return null // decayed
      if (em < 1.0e-6) em = 1.0e-6
      mm += no * templ
      var xlm = mm + argpm + nodem
      val nodemR = nodem % TWO_PI
      argpm %= TWO_PI
      xlm %= TWO_PI
      mm = (xlm - argpm - nodemR) % TWO_PI

      // Long-period periodics (near-Earth: inclination unchanged).
      val axnl = em * cos(argpm)
      val temp4 = 1.0 / (am * (1.0 - em * em))
      val aynl = em * sin(argpm) + temp4 * aycof
      val xl = mm + argpm + nodemR + temp4 * xlcof * axnl

      // Kepler's equation for (E + omega).
      val u = (xl - nodemR) % TWO_PI
      var eo1 = u
      var tem5 = 9999.9
      var ktr = 1
      var sineo1 = 0.0
      var coseo1 = 0.0
      while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
        ToolsWork.checkpoint()
        sineo1 = sin(eo1)
        coseo1 = cos(eo1)
        tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
        tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
        if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0) 0.95 else -0.95
        eo1 += tem5
        ktr++
      }

      // Short-period preliminary quantities.
      val ecose = axnl * coseo1 + aynl * sineo1
      val esine = axnl * sineo1 - aynl * coseo1
      val el2 = axnl * axnl + aynl * aynl
      val pl = am * (1.0 - el2)
      if (pl < 0.0) return null
      val rl = am * (1.0 - ecose)
      val betal = sqrt(1.0 - el2)
      val tmp = esine / (1.0 + betal)
      val sinu = am / rl * (sineo1 - aynl - axnl * tmp)
      val cosu = am / rl * (coseo1 - axnl + aynl * tmp)
      var su = atan2(sinu, cosu)
      val sin2u = (cosu + cosu) * sinu
      val cos2u = 1.0 - 2.0 * sinu * sinu
      val temp = 1.0 / pl
      val temp1 = 0.5 * J2 * temp
      val temp2 = temp1 * temp

      // Apply short-period periodics (no deep-space corrections).
      val mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
      su -= 0.25 * temp2 * x7thm1 * sin2u
      val xnode = nodemR + 1.5 * temp2 * cos(inclo) * sin2u
      val xinc = inclo + 1.5 * temp2 * cos(inclo) * sin(inclo) * cos2u

      // Orientation vectors -> position.
      val sinsu = sin(su)
      val cossu = cos(su)
      val snod = sin(xnode)
      val cnod = cos(xnode)
      val sini = sin(xinc)
      val cosi = cos(xinc)
      val xmx = -snod * cosi
      val xmy = cnod * cosi
      val ux = xmx * sinsu + cnod * cossu
      val uy = xmy * sinsu + snod * cossu
      val uz = sini * sinsu
      return doubleArrayOf(
          mrt * ux * RADIUS_EARTH_KM,
          mrt * uy * RADIUS_EARTH_KM,
          mrt * uz * RADIUS_EARTH_KM,
      )
    }

    companion object {
      /** Parse the two TLE data lines and run SGP4 initialisation. */
      fun fromTle(line1: String, line2: String): Sat {
        // --- field extraction (fixed columns per the TLE spec) ---
        val epochYr = line1.substring(18, 20).trim().toInt()
        val epochDay = line1.substring(20, 32).trim().toDouble()
        val bstar = expField(line1.substring(53, 61))
        val inclo = line2.substring(8, 16).trim().toDouble() * DEG
        val nodeo = line2.substring(17, 25).trim().toDouble() * DEG
        val ecco = ("0." + line2.substring(26, 33).trim()).toDouble()
        val argpo = line2.substring(34, 42).trim().toDouble() * DEG
        val mo = line2.substring(43, 51).trim().toDouble() * DEG
        val noRevPerDay = line2.substring(52, 63).trim().toDouble()
        val noKozai = noRevPerDay * TWO_PI / 1440.0 // rad/min

        val year = if (epochYr < 57) 2000 + epochYr else 1900 + epochYr
        val jdEpoch = jdFromEpoch(year, epochDay)

        return init(jdEpoch, ecco, inclo, nodeo, argpo, mo, noKozai, bstar)
      }

      /** SGP4 initialisation (near-Earth), WGS-72 constants. */
      private fun init(
          jdEpoch: Double,
          ecco: Double,
          inclo: Double,
          nodeo: Double,
          argpo: Double,
          mo: Double,
          noKozai: Double,
          bstar: Double,
      ): Sat {
        val ss = 78.0 / RADIUS_EARTH_KM + 1.0
        val qzms2t = ((120.0 - 78.0) / RADIUS_EARTH_KM).pow(4.0)
        val x2o3 = 2.0 / 3.0

        val cosio = cos(inclo)
        val cosio2 = cosio * cosio
        val eccsq = ecco * ecco
        val omeosq = 1.0 - eccsq
        val rteosq = sqrt(omeosq)

        // Recover the un-Kozai'd mean motion and semi-major axis.
        val ak = (XKE / noKozai).pow(x2o3)
        val d1 = 0.75 * J2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        val adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0))
        del = d1 / (adel * adel)
        val no = noKozai / (1.0 + del)

        val ao = (XKE / no).pow(x2o3)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1.0 - 5.0 * cosio2
        val con41 = 3.0 * cosio2 - 1.0
        val posq = po * po
        val rp = ao * (1.0 - ecco)

        var sfour = ss
        var qzms24 = qzms2t
        val perige = (rp - 1.0) * RADIUS_EARTH_KM
        if (perige < 156.0) {
          sfour = perige - 78.0
          if (perige < 98.0) sfour = 20.0
          qzms24 = ((120.0 - sfour) / RADIUS_EARTH_KM).pow(4.0)
          sfour = sfour / RADIUS_EARTH_KM + 1.0
        }
        val pinvsq = 1.0 / posq
        val tsi = 1.0 / (ao - sfour)
        val eta = ao * ecco * tsi
        val etasq = eta * eta
        val eeta = ecco * eta
        val psisq = abs(1.0 - etasq)
        val coef = qzms24 * tsi.pow(4.0)
        val coef1 = coef / psisq.pow(3.5)
        val cc2 =
            coef1 * no *
                (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                    0.375 * J2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)))
        val cc1 = bstar * cc2
        var cc3 = 0.0
        if (ecco > 1.0e-4) cc3 = -2.0 * coef * tsi * J3OJ2 * no * sinio / ecco
        val x1mth2 = 1.0 - cosio2
        val cc4 =
            2.0 * no * coef1 * ao * omeosq *
                (eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq) -
                    J2 * tsi / (ao * psisq) *
                        (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                            0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo)))
        val cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
        val cosio4 = cosio2 * cosio2
        val temp1 = 1.5 * J2 * pinvsq * no
        val temp2 = 0.5 * temp1 * J2 * pinvsq
        val temp3 = -0.46875 * J4 * pinvsq * pinvsq * no
        val mdot =
            no + 0.5 * temp1 * rteosq * con41 +
                0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
        val argpdot =
            -0.5 * temp1 * con42 +
                0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
                temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
        val xhdot1 = -temp1 * cosio
        val nodedot =
            xhdot1 +
                (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
        val omgcof = bstar * cc3 * cos(argpo)
        var xmcof = 0.0
        if (ecco > 1.0e-4) xmcof = -x2o3 * coef * bstar / eeta
        val nodecf = 3.5 * omeosq * xhdot1 * cc1
        val t2cof = 1.5 * cc1
        val xlcof = -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
        val aycof = -0.5 * J3OJ2 * sinio
        val delmo = (1.0 + eta * cos(mo)).pow(3.0)
        val sinmao = sin(mo)
        val x7thm1 = 7.0 * cosio2 - 1.0

        var isimp = false
        var d2 = 0.0
        var d3 = 0.0
        var d4 = 0.0
        var t3cof = 0.0
        var t4cof = 0.0
        var t5cof = 0.0
        if (rp < 220.0 / RADIUS_EARTH_KM + 1.0) {
          isimp = true
        } else {
          val cc1sq = cc1 * cc1
          d2 = 4.0 * ao * tsi * cc1sq
          val temp = d2 * tsi * cc1 / 3.0
          d3 = (17.0 * ao + sfour) * temp
          d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1
          t3cof = d2 + 2.0 * cc1sq
          t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq))
          t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d3 + cc1sq))
        }

        return Sat(
            jdEpoch = jdEpoch,
            ecco = ecco,
            inclo = inclo,
            nodeo = nodeo,
            argpo = argpo,
            mo = mo,
            no = no,
            ao = ao,
            con41 = con41,
            x1mth2 = x1mth2,
            x7thm1 = x7thm1,
            xlcof = xlcof,
            aycof = aycof,
            cc1 = cc1,
            cc4 = cc4,
            cc5 = cc5,
            t2cof = t2cof,
            mdot = mdot,
            argpdot = argpdot,
            nodedot = nodedot,
            nodecf = nodecf,
            omgcof = omgcof,
            xmcof = xmcof,
            eta = eta,
            sinmao = sinmao,
            delmo = delmo,
            bstar = bstar,
            isimp = isimp,
            d2 = d2,
            d3 = d3,
            d4 = d4,
            t3cof = t3cof,
            t4cof = t4cof,
            t5cof = t5cof,
        )
      }

      /** TLE exponent field, e.g. "-11606-4" -> -0.11606e-4, " 28098-4" -> 0.28098e-4. */
      private fun expField(raw: String): Double {
        val s = raw.trim()
        if (s.isEmpty() || s == "00000-0" || s == "00000+0") return 0.0
        val sign = if (s.startsWith("-")) -1.0 else 1.0
        val body = s.trimStart('+', '-')
        val expIdx = body.indexOfLast { it == '+' || it == '-' }
        val mantissa = ("0." + body.substring(0, expIdx)).toDouble()
        val exp = body.substring(expIdx).toInt()
        return sign * mantissa * 10.0.pow(exp.toDouble())
      }

      /** Julian date from a TLE epoch (4-digit year + fractional day-of-year, where
       * day 1.0 = Jan 1 00:00 UT). Standard Gregorian conversion. */
      private fun jdFromEpoch(year: Int, dayOfYear: Double): Double {
        // JD of Jan 1, 00:00 UT of `year`.
        val a = (14 - 1) / 12 // month = 1
        val y = year + 4800 - a
        val m = 1 + 12 * a - 3
        val jdn = 1 + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
        val jdJan1 = jdn - 0.5
        return jdJan1 + (dayOfYear - 1.0)
      }
    }
  }

  // WGS-72 gravitational constants (the model TLEs are fit against).
  private val XKE = 60.0 / sqrt(RADIUS_EARTH_KM * RADIUS_EARTH_KM * RADIUS_EARTH_KM / 398600.8)
  private const val J2 = 0.001082616
  private const val J3 = -0.00000253881
  private const val J4 = -0.00000165597
  private const val J3OJ2 = J3 / J2
}
