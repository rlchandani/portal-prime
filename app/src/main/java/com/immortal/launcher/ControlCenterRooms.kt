/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.ln
import kotlin.math.pow

/**
 * What each room looks like on the plan right now (pure, unit tested): the light glows (each
 * light's real colour and brightness at its position), temperature, open things, motion, locks and
 * power. The canvas only draws these; it never reads entities itself.
 */
internal data class CcGlow(val entityId: String, val fx: Float, val fy: Float, val rgb: Int, val strength: Float)

internal data class CcMarker(val entityId: String, val fx: Float, val fy: Float, val kind: CcKind, val active: Boolean)

internal data class CcRoomView(
    val room: CcRoom,
    val lightsOn: Int,
    val lights: Int,
    val glows: List<CcGlow>,
    val markers: List<CcMarker>,
    val temperature: Double?,
    val humidity: Int?,
    val motion: Boolean,
    val openCount: Int,
    val powerW: Double?,
    val climateId: String?,
) {
  /** "71° · 2 lights on" under the room name. */
  fun caption(unit: String = "°"): String {
    val parts = mutableListOf<String>()
    temperature?.let { parts += "${Math.round(it)}$unit" }
    if (lightsOn > 0) parts += if (lightsOn == 1) "1 light on" else "$lightsOn lights on"
    return parts.joinToString(" · ")
  }
}

internal object CcRooms {
  private const val WARM_DEFAULT_K = 2700
  private const val MIN_STRENGTH = 0.18f
  private const val PCT = 100f
  private const val MAX_TEMP_K = 40000.0

  fun views(floor: CcFloor, entities: Map<String, CcEntity>, climates: List<CcEntity> = emptyList()): List<CcRoomView> =
      floor.rooms.map { view(it, entities, climates) }

  fun view(room: CcRoom, entities: Map<String, CcEntity>, climates: List<CcEntity> = emptyList()): CcRoomView {
    val placed = room.devices.mapNotNull { p -> entities[p.entityId]?.let { p to it } }
    val lights = placed.filter { (_, e) -> e.domain == "light" || (e.domain == "switch" && "light" in e.id) }
    val glows = lights.filter { (_, e) -> e.isOn }.map { (p, e) -> CcGlow(e.id, p.fx, p.fy, glowColor(e), glowStrength(e)) }
    val markers = placed.map { (p, e) ->
      val kind = CcKind.of(e)
      CcMarker(e.id, p.fx, p.fy, kind, active = when (kind) { CcKind.MOTION -> e.isOn; CcKind.LOCK -> e.state == "locked"; else -> e.isOn || e.isOpen })
    }
    val temps = placed.map { it.second }.filter { CcKind.of(it) == CcKind.TEMPERATURE }.mapNotNull { it.numeric }
    val climate = placed.map { it.second }.firstOrNull { it.domain == "climate" }
        ?: climates.firstOrNull { c -> room.devices.any { it.entityId == c.id } }
    val humidity = placed.map { it.second }.firstOrNull { CcKind.of(it) == CcKind.HUMIDITY }?.numeric?.toInt() ?: climate?.humidity
    val power = placed.map { it.second }.filter { CcKind.of(it) == CcKind.POWER }.mapNotNull { watts(it) }
    return CcRoomView(
        room = room,
        lightsOn = glows.size,
        lights = lights.size,
        glows = glows,
        markers = markers,
        temperature = temps.takeIf { it.isNotEmpty() }?.average() ?: climate?.currentTemp,
        humidity = humidity,
        motion = placed.any { (_, e) -> CcKind.of(e) == CcKind.MOTION && e.isOn },
        openCount = placed.count { (_, e) -> e.isOpen && e.domain != "lock" },
        powerW = power.takeIf { it.isNotEmpty() }?.sum(),
        climateId = climate?.id,
    )
  }

  /** Watts from a power sensor, converting kW. */
  fun watts(e: CcEntity): Double? {
    val v = e.numeric ?: return null
    return if (e.unit.equals("kW", ignoreCase = true)) v * 1000.0 else v
  }

  fun glowStrength(e: CcEntity): Float = ((e.brightnessPct ?: PCT.toInt()) / PCT).coerceIn(MIN_STRENGTH, 1f)

  /** The light's own colour: rgb if colour, else its white temperature, else warm white. */
  fun glowColor(e: CcEntity): Int = e.rgb?.takeIf { e.colorTempK == null || e.attrs["color_mode"] != "color_temp" }
      ?: kelvinToRgb(e.colorTempK ?: WARM_DEFAULT_K)

  /** Tanner Helland's black-body approximation, good enough for a glow. Packed 0xRRGGBB. */
  fun kelvinToRgb(kelvin: Int): Int {
    val t = kelvin.coerceIn(1000, MAX_TEMP_K.toInt()) / 100.0
    val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
    val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
    val b = when { t >= 66 -> 255.0; t <= 19 -> 0.0; else -> 138.5177312231 * ln(t - 10) - 305.0447927307 }
    fun c(v: Double) = v.coerceIn(0.0, 255.0).toInt()
    return (c(r) shl 16) or (c(g) shl 8) or c(b)
  }

  /** Climate lens: 0 (cold, blue) … 1 (warm, amber) between 16 °C/61 °F and 24 °C/75 °F. */
  fun heat(temp: Double, fahrenheit: Boolean): Float {
    val (lo, hi) = if (fahrenheit) 61.0 to 75.0 else 16.0 to 24.0
    return ((temp - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
  }
}
