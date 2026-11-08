/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Household content that Guest mode keeps off the screen. Playback controls are not listed. */
internal enum class GuestContent {
  /** Photo frame, screensaver photos and memories, the Home photo widget. */
  PHOTOS,
  /** Calendar events on Home and on the screensaver. */
  CALENDAR,
  /** The shared household list. */
  HOUSEHOLD_LIST,
  /** Sticky / household notes. */
  NOTES,
  /** Notes attached to photos shared from a phone. */
  SHARED_PHOTO_NOTES,
  /** Music library, playlists and signed-in accounts. */
  MUSIC_LIBRARY,
}

/** When Guest mode ends on its own. */
internal sealed interface GuestSchedule {
  /** Local epoch-millis end, or null for "until someone ends it". */
  fun endMs(nowMs: Long, zone: ZoneId): Long?

  data object UntilEnded : GuestSchedule {
    override fun endMs(nowMs: Long, zone: ZoneId): Long? = null
  }

  /** "Until tomorrow 11:00": the given time on the next calendar day. */
  data class UntilTomorrow(val hour: Int, val minute: Int) : GuestSchedule {
    override fun endMs(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1)
            .atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
  }

  /** "For 2 days": whole days from now. */
  data class ForDays(val days: Int) : GuestSchedule {
    override fun endMs(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).plusDays(days.toLong()).toInstant().toEpochMilli()
  }

  companion object {
    const val MAX_DAYS = 14

    /** Validated from wire/UI input: exactly one of days or hour+minute, or neither. Null = invalid. */
    fun parse(days: Int?, hour: Int?, minute: Int?): GuestSchedule? = when {
      days != null && (hour != null || minute != null) -> null
      days != null -> if (days in 1..MAX_DAYS) ForDays(days) else null
      hour != null && minute != null -> if (hour in 0..23 && minute in 0..59) UntilTomorrow(hour, minute) else null
      hour != null || minute != null -> null
      else -> UntilEnded
    }
  }
}

/** Immutable Guest mode snapshot; [untilMs] null means it runs until ended. */
internal data class GuestModeState(val active: Boolean, val untilMs: Long?) {
  fun isOn(nowMs: Long): Boolean = active && (untilMs == null || nowMs < untilMs)

  fun encode(): String = if (!active) "off" else "on:${untilMs ?: ""}"

  companion object {
    val OFF = GuestModeState(active = false, untilMs = null)

    fun start(schedule: GuestSchedule, nowMs: Long, zone: ZoneId): GuestModeState =
        GuestModeState(active = true, untilMs = schedule.endMs(nowMs, zone))

    fun decode(text: String?): GuestModeState {
      if (text == null || !text.startsWith("on:")) return OFF
      val tail = text.removePrefix("on:")
      if (tail.isEmpty()) return GuestModeState(true, null)
      return tail.toLongOrNull()?.let { GuestModeState(true, it) } ?: OFF
    }
  }
}

/**
 * The single source of truth for "a guest is here". Every surface that shows household content
 * (photo frame, screensaver, Home widgets, music library) asks [hides] instead of keeping its own
 * flag; [GuestModeController] persists and schedules it. Plain JVM so policies and tests can read it.
 */
internal object GuestMode {
  private val mutable = MutableStateFlow(GuestModeState.OFF)
  val state: StateFlow<GuestModeState> = mutable.asStateFlow()

  fun set(value: GuestModeState) {
    mutable.value = value
  }

  fun isOn(nowMs: Long = System.currentTimeMillis()): Boolean = mutable.value.isOn(nowMs)

  /** The gating rule. Exhaustive on purpose: a new [GuestContent] must decide here. */
  fun hides(content: GuestContent, nowMs: Long = System.currentTimeMillis()): Boolean =
      isOn(nowMs) && when (content) {
        GuestContent.PHOTOS, GuestContent.CALENDAR, GuestContent.HOUSEHOLD_LIST, GuestContent.NOTES,
        GuestContent.SHARED_PHOTO_NOTES, GuestContent.MUSIC_LIBRARY -> true
      }
}
