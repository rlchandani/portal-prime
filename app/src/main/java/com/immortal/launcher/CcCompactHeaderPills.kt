/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * How the compact Control Center header's status pills shorten so they fit (pure, JVM-tested)
 * instead of scrolling sideways (Mini upright, its own pill row) or squeezing the screen title
 * (Mini turned, one row beside the title). Widths are in the CC's scaled units
 * ([CcCompactScale]; the 800 dp Mini upright is a ~941-unit canvas, turned ~1707).
 *
 * Shortening steps, in order, until the row fits: the weather shows just its temperature, the
 * alarm just "Armed"/"Disarmed", then fewer avatars (down to the count alone). Spoken descriptions
 * always stay complete.
 */
internal object CcCompactHeaderPills {
  /** Gap between pills and the upright header's side padding (each side), in CC units. */
  const val GAP = 12.0
  const val SIDE_PADDING = 30.0

  /** The turned header ([CcHeader]): its gap, side padding and what the title + Ask Jarvis keep. */
  const val ROW_GAP = 18.0
  const val ROW_SIDE_PADDING = 34.0
  /** Glyph (30) + gap (16) + room for a ~15-character serif title at 46 sp ("Edit floor plan"). */
  const val TITLE_RESERVE = 46.0 + 360.0
  /** Ask Jarvis: 18 + 40 orb + 12 + label (23 sp semibold) + 28. */
  const val JARVIS = 98.0 + 10 * 14.0

  /** Units the turned header's pills may take in a row [rowWidth] wide (title, Jarvis and gaps removed). */
  fun rowPillBudget(rowWidth: Double): Double = rowWidth - 2 * ROW_SIDE_PADDING - TITLE_RESERVE - JARVIS - 2 * ROW_GAP

  const val MAX_AVATARS = 4

  // CcPill: 22 padding each side + 10 between children; labels are 22 sp regular.
  private const val PILL_CHROME = 44.0 + 10.0
  private val LABEL = CompactRowFit.Spec(charDp = 12.5, chromeDp = 0.0)
  private const val DOT = 22.0
  private const val ICON = 28.0
  private const val WARN_ICON = 26.0
  private const val AVATAR = 36.0
  private const val AVATAR_OVERLAP = 10.0

  /** What the header has to show; null fields have no pill. */
  data class Input(
      val live: String,
      val people: String? = null,
      val peopleAvatars: Int = 0,
      val alarm: String? = null,
      val weatherTemp: String? = null,
      val weatherText: String? = null,
      val debug: String? = null,
  )

  /** The chosen variant for each pill. */
  data class Plan(val weather: String?, val alarmShort: Boolean, val avatars: Int)

  /** The first (fullest) variant whose pills fit [available] units with [gap] between them. */
  fun plan(input: Input, available: Double, fontScale: Float = 1f, gap: Double = GAP): Plan {
    val avatars = input.peopleAvatars.coerceIn(0, MAX_AVATARS)
    val full = Plan(weather(input, short = false), alarmShort = false, avatars = avatars)
    val steps = buildList {
      add(full)
      add(full.copy(weather = weather(input, short = true)))
      add(last().copy(alarmShort = true))
      for (n in avatars - 1 downTo 0) add(last().copy(avatars = n))
    }
    return steps.firstOrNull { width(input, it, fontScale, gap) <= available } ?: steps.last()
  }

  /** The row's estimated width for [plan]. */
  fun width(input: Input, plan: Plan, fontScale: Float = 1f, gap: Double = GAP): Double {
    fun pill(lead: Double, label: String) = lead + PILL_CHROME + LABEL.width(label, fontScale)
    val pills = listOfNotNull(
        pill(DOT, input.live),
        input.people?.let { pill(avatarsWidth(plan.avatars), it) },
        input.alarm?.let { pill(ICON, if (plan.alarmShort) shortAlarm(it) else it) },
        plan.weather?.let { pill(ICON, it) },
        input.debug?.let { pill(WARN_ICON, it) },
    )
    return CompactRowFit.rowWidth(pills, gap)
  }

  /** "Armed · Away" → "Armed"; labels without a mode stay as they are. */
  fun shortAlarm(label: String): String = label.substringBefore(" · ")

  private fun weather(input: Input, short: Boolean): String? {
    if (short && input.weatherTemp != null) return input.weatherTemp
    return listOfNotNull(input.weatherTemp, input.weatherText).joinToString(" · ").ifEmpty { null }
  }

  private fun avatarsWidth(n: Int) = if (n <= 0) 0.0 else n * AVATAR - (n - 1) * AVATAR_OVERLAP
}
