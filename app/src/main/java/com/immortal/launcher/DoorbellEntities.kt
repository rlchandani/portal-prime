/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Locale

/**
 * Which Home Assistant entity ids the doorbell accepts, shared by the settings validation, the
 * picker, the listener and the household wire. Pure.
 */
internal object DoorbellEntities {
  const val EVENT = "event"
  const val BINARY_SENSOR = "binary_sensor"
  const val CAMERA = "camera"
  const val MAX_MOTION = 6
  private const val MAX_ID = 128
  private val ENTITY = Regex("^[a-z0-9_]{1,32}\\.[a-z0-9_]{1,$MAX_ID}$")
  private val SEPARATORS = Regex("[,\\s]+")

  fun domainOf(id: String): String = id.substringBefore('.', "")

  fun isEntity(id: String?): Boolean = id != null && ENTITY.matches(id)

  /** A doorbell press: an `event.*` (newer integrations) or a `binary_sensor.*` that turns on. */
  fun isTrigger(id: String?): Boolean = isEntity(id) && domainOf(id!!) in setOf(EVENT, BINARY_SENSOR)

  fun isCamera(id: String?): Boolean = isEntity(id) && domainOf(id!!) == CAMERA

  fun isMotion(id: String?): Boolean = isEntity(id) && domainOf(id!!) == BINARY_SENSOR

  /** Settings guards: blank clears the choice. */
  fun validTriggerSetting(text: String): Boolean = text.isBlank() || isTrigger(text.trim())

  fun validCameraSetting(text: String): Boolean = text.isBlank() || isCamera(text.trim())

  fun validMotionSetting(text: String): Boolean {
    val parts = text.trim().split(SEPARATORS).filter { it.isNotBlank() }
    return parts.size <= MAX_MOTION && parts.all(::isMotion)
  }

  /** The motion sensors in a saved list (invalid entries dropped, no repeats, at most [MAX_MOTION]). */
  fun motionList(text: String): List<String> =
      text.trim().split(SEPARATORS).filter(::isMotion).distinct().take(MAX_MOTION)

  /** "camera.front_door" -> "Front door": a readable camera name without asking Home Assistant. */
  fun prettyName(id: String): String {
    val words = id.substringAfter('.').replace('_', ' ').trim()
    if (words.isEmpty()) return "Camera"
    return words.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
  }
}
