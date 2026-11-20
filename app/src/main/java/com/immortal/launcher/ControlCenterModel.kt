/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
/**
 * Home Control Center data model: immutable snapshots of Home Assistant entities as the plan,
 * room sheet and policies read them. Built by [CcEntityParser] from either a full HA state object
 * or the compressed `subscribe_entities` form; [attrs] keeps the raw attributes so a later
 * compressed diff can be applied without a fresh snapshot.
 */
@Immutable
internal data class CcEntity(
    val id: String,
    val state: String,
    val attrs: Map<String, Any?> = emptyMap(),
    /** Epoch ms of the last state change (HA `last_changed`). */
    val lastChanged: Long = 0L,
    /** HA user id behind the last change, when HA recorded one (context.user_id). */
    val userId: String? = null,
) {
  val domain: String get() = id.substringBefore('.', "")
  val name: String get() = (attrs["friendly_name"] as? String)?.takeIf { it.isNotBlank() } ?: prettyId(id)
  val deviceClass: String? get() = attrs["device_class"] as? String
  val unit: String? get() = attrs["unit_of_measurement"] as? String
  val unavailable: Boolean get() = state == "unavailable" || state == "unknown" || state.isEmpty()

  val isOn: Boolean get() = state in ON_STATES
  /** Doors, windows, covers and valves that are open (or opening). */
  val isOpen: Boolean
    get() = when (domain) {
      "cover" -> state == "open" || state == "opening" || state == "closing"
      "valve" -> state == "open" || state == "opening"
      "lock" -> state == "unlocked" || state == "open" || state == "unlocking"
      "binary_sensor" -> deviceClass in OPENING_CLASSES && state == "on"
      else -> false
    }

  val numeric: Double? get() = state.toDoubleOrNull()
  val brightnessPct: Int? get() = (attrs["brightness"] as? Number)?.let { Math.round(it.toDouble() * PCT / BYTE_MAX).toInt() }
  /** Packed 0xRRGGBB from `rgb_color`, if the light reports one. */
  val rgb: Int?
    get() = (attrs["rgb_color"] as? List<*>)?.takeIf { it.size == RGB_SIZE }?.let { c ->
      val r = (c[0] as? Number)?.toInt() ?: return null
      val g = (c[1] as? Number)?.toInt() ?: return null
      val b = (c[2] as? Number)?.toInt() ?: return null
      (r.coerceIn(0, BYTE) shl 16) or (g.coerceIn(0, BYTE) shl 8) or b.coerceIn(0, BYTE)
    }
  val colorTempK: Int? get() = (attrs["color_temp_kelvin"] as? Number)?.toInt()
  val colorModes: List<String> get() = (attrs["supported_color_modes"] as? List<*>)?.filterIsInstance<String>().orEmpty()
  val supportsColor: Boolean get() = colorModes.any { it in COLOR_MODES }
  val supportsColorTemp: Boolean get() = "color_temp" in colorModes || supportsColor && attrs.containsKey("min_color_temp_kelvin")
  val dimmable: Boolean get() = colorModes.any { it != "onoff" } || attrs.containsKey("brightness")
  val minKelvin: Int get() = (attrs["min_color_temp_kelvin"] as? Number)?.toInt() ?: DEFAULT_MIN_K
  val maxKelvin: Int get() = (attrs["max_color_temp_kelvin"] as? Number)?.toInt() ?: DEFAULT_MAX_K

  val currentTemp: Double? get() = (attrs["current_temperature"] as? Number)?.toDouble()
  val targetTemp: Double? get() = (attrs["temperature"] as? Number)?.toDouble()
  /** HA's min/max, else a unit-aware default (a setpoint above 40 can only be °F). */
  val minTemp: Double get() = (attrs["min_temp"] as? Number)?.toDouble() ?: if (looksFahrenheit) DEFAULT_MIN_TEMP_F else DEFAULT_MIN_TEMP
  val maxTemp: Double get() = (attrs["max_temp"] as? Number)?.toDouble() ?: if (looksFahrenheit) DEFAULT_MAX_TEMP_F else DEFAULT_MAX_TEMP
  private val looksFahrenheit: Boolean get() = (targetTemp ?: currentTemp ?: 0.0) > CELSIUS_CEILING
  val tempStep: Double get() = (attrs["target_temp_step"] as? Number)?.toDouble() ?: 1.0
  val hvacModes: List<String> get() = (attrs["hvac_modes"] as? List<*>)?.filterIsInstance<String>().orEmpty()
  val hvacAction: String? get() = attrs["hvac_action"] as? String
  val humidity: Int? get() = ((attrs["current_humidity"] ?: attrs["humidity"]) as? Number)?.toInt()

  /** Cover position 0 (closed) … 100 (open); falls back to the open/closed state. */
  val position: Int?
    get() = (attrs["current_position"] as? Number)?.toInt()
        ?: when (state) { "open" -> PCT; "closed" -> 0; else -> null }
  val percentage: Int? get() = (attrs["percentage"] as? Number)?.toInt()

  val mediaTitle: String? get() = attrs["media_title"] as? String
  val mediaArtist: String? get() = (attrs["media_artist"] ?: attrs["media_album_name"]) as? String
  val volume: Float? get() = (attrs["volume_level"] as? Number)?.toFloat()

  val supportedFeatures: Int get() = (attrs["supported_features"] as? Number)?.toInt() ?: 0
  val codeFormat: String? get() = attrs["code_format"] as? String
  val changedBy: String? get() = (attrs["changed_by"] as? String)?.takeIf { it.isNotBlank() }
  val battery: Int? get() = (attrs["battery_level"] as? Number)?.toInt()
  /** Scene members (HA lists them in the scene's `entity_id` attribute). */
  val members: List<String> get() = (attrs["entity_id"] as? List<*>)?.filterIsInstance<String>().orEmpty()

  companion object {
    private const val PCT = 100
    private const val BYTE = 255
    private const val BYTE_MAX = 255.0
    private const val RGB_SIZE = 3
    const val DEFAULT_MIN_K = 2000
    const val DEFAULT_MAX_K = 6500
    const val DEFAULT_MIN_TEMP = 7.0
    const val DEFAULT_MAX_TEMP = 35.0
    const val DEFAULT_MIN_TEMP_F = 45.0
    const val DEFAULT_MAX_TEMP_F = 95.0
    private const val CELSIUS_CEILING = 40.0
    private val ON_STATES = setOf("on", "playing", "open", "heat", "cool", "heat_cool", "auto", "dry", "fan_only")
    val OPENING_CLASSES = setOf("door", "window", "garage_door", "opening", "garage")
    private val COLOR_MODES = setOf("hs", "rgb", "rgbw", "rgbww", "xy")

    fun prettyId(id: String): String =
        id.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
  }
}

/** What the plan can do with an entity — decides its tile, control and glyph. */
internal enum class CcKind {
  LIGHT, SWITCH, FAN, LOCK, GARAGE, COVER, CLIMATE, MEDIA, CAMERA, VALVE, ALARM,
  DOOR, WINDOW, MOTION, LEAK, SMOKE, TEMPERATURE, HUMIDITY, POWER, ENERGY, BATTERY, APPLIANCE,
  SCENE, SCRIPT, PERSON, SENSOR, OTHER;

  companion object {
    private val APPLIANCE_HINTS = listOf("washer", "washing", "dryer", "dishwasher", "oven")

    fun of(e: CcEntity): CcKind = when (e.domain) {
      "light" -> LIGHT
      "switch", "input_boolean" -> if (APPLIANCE_HINTS.any { it in e.id }) APPLIANCE else SWITCH
      "fan" -> FAN
      "lock" -> LOCK
      "cover" -> if (e.deviceClass == "garage" || e.deviceClass == "gate") GARAGE else COVER
      "climate" -> CLIMATE
      "media_player" -> MEDIA
      "camera" -> CAMERA
      "valve" -> VALVE
      "alarm_control_panel" -> ALARM
      "scene" -> SCENE
      "script" -> SCRIPT
      "person" -> PERSON
      "binary_sensor" -> binary(e)
      "sensor" -> sensor(e)
      else -> OTHER
    }

    private fun binary(e: CcEntity): CcKind = when (e.deviceClass) {
      "door", "opening" -> DOOR
      "garage_door", "garage" -> GARAGE
      "window" -> WINDOW
      "motion", "occupancy", "presence" -> MOTION
      "moisture" -> LEAK
      "smoke", "gas", "carbon_monoxide", "safety" -> SMOKE
      "battery" -> BATTERY
      else -> if (APPLIANCE_HINTS.any { it in e.id }) APPLIANCE else SENSOR
    }

    private fun sensor(e: CcEntity): CcKind = when (e.deviceClass) {
      "temperature" -> TEMPERATURE
      "humidity" -> HUMIDITY
      "power" -> POWER
      "energy" -> ENERGY
      "battery" -> BATTERY
      else -> if (APPLIANCE_HINTS.any { it in e.id }) APPLIANCE else SENSOR
    }
  }
}

/** Kinds a person touches on the room sheet (sensors are listed, never toggled). */
internal val CcKind.controllable: Boolean
  get() = this in setOf(CcKind.LIGHT, CcKind.SWITCH, CcKind.FAN, CcKind.LOCK, CcKind.GARAGE, CcKind.COVER,
      CcKind.CLIMATE, CcKind.MEDIA, CcKind.VALVE, CcKind.ALARM, CcKind.APPLIANCE)

/** Connection to Home Assistant as the screens show it (see the mock's States group). */
internal enum class CcPhase { NOT_CONFIGURED, CONNECTING, LIVE, STALE, UNREACHABLE, AUTH_FAILED, OFFLINE }

@Immutable
internal data class CcConnection(
    val phase: CcPhase = CcPhase.CONNECTING,
    /** Honest progress while connecting, e.g. "Loading rooms". */
    val step: String = "",
    /** Epoch ms when data was last confirmed live (for "Last known · 7:31 pm"). */
    val lastLiveAt: Long = 0L,
    /** Epoch ms of the next automatic retry while unreachable. */
    val retryAt: Long = 0L,
) {
  /** Controls act only on live data; everything else disables them with [disabledReason]. */
  val canControl: Boolean get() = phase == CcPhase.LIVE

  val disabledReason: String?
    get() = when (phase) {
      CcPhase.LIVE -> null
      CcPhase.CONNECTING -> "Connecting to Home Assistant"
      CcPhase.STALE -> "Reconnecting — values may be out of date"
      CcPhase.UNREACHABLE -> "Home Assistant isn't answering"
      CcPhase.AUTH_FAILED -> "The Home Assistant token was refused"
      CcPhase.OFFLINE -> "This Portal is offline"
      CcPhase.NOT_CONFIGURED -> "Home Assistant isn't set up"
    }
}

/** HA's floor/area/device/entity registries reduced to what the plan needs. */
@Immutable
internal data class CcRegistry(
    val floors: List<CcHaFloor> = emptyList(),
    val areas: List<CcHaArea> = emptyList(),
    /** entity id → area id (the entity's own area, else its device's). */
    val entityArea: Map<String, String> = emptyMap(),
    /** entity id → device class from the registry (known before the state arrives). */
    val entityClass: Map<String, String> = emptyMap(),
    /** Entities the registry lists as usable (not disabled, hidden, diagnostic or config). */
    val entities: Set<String> = emptySet(),
    /** Enabled, visible binary sensors filed as diagnostics (where integrations put tamper sensors). */
    val diagnostics: Set<String> = emptySet(),
    /**
     * Binary-sensor classes from `get_entries` (the list call omits them). Read through [classOf]
     * by the screens and by the background banner set ([CcInterest.background]).
     */
    val fetchedClass: Map<String, String> = emptyMap(),
    /** camera entity → its integration (`mqtt` for ring-mqtt, `wyze_ha`, …), to explain what it can do. */
    val cameraPlatform: Map<String, String> = emptyMap(),
    /** camera entity → "take snapshot" buttons on the same device (ring-mqtt), for the Refresh button. */
    val snapshotButtons: Map<String, List<String>> = emptyMap(),
    /** The car, its home charger and a price entity, for the Tesla screen (diagnostic ones included). */
    val tesla: CcTeslaIds = CcTeslaIds.NONE,
) {
  fun areaOf(entityId: String): String? = entityArea[entityId]
  /** Area id → the binary sensors filed under it (motion/occupancy candidates), built once per registry. */
  val areaSensors: Map<String, List<String>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    entityArea.entries.filter { it.key.startsWith("binary_sensor.") }.groupBy({ it.value }, { it.key })
  }
  /** The best class known before the state arrives. */
  fun classOf(entityId: String): String? = entityClass[entityId] ?: fetchedClass[entityId]
  /** Binary sensors whose class we still need to ask HA for. */
  val classless: List<String> get() = (entities + diagnostics).filter { it.startsWith("binary_sensor.") && it !in entityClass }.sorted()
  fun areaName(entityId: String): String? = areaOf(entityId)?.let { a -> areas.firstOrNull { it.id == a }?.name }
  fun areasOn(floorId: String?): List<CcHaArea> = areas.filter { it.floorId == floorId }
}

@Immutable
internal data class CcHaFloor(val id: String, val name: String, val level: Int?)
@Immutable
internal data class CcHaArea(val id: String, val name: String, val floorId: String?)
