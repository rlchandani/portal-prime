/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

/**
 * What the "For you right now" row shows (mock `mocks/jarvis-personalization-20261122`, Home): at most [MAX] chips,
 * the best one with the ember outline, each with a short "why" line. One chip per thing it opens (Jarvis is counted
 * both as a conversation and as an app). Household chips never say "you". Pure.
 */
internal object ForYouChips {
  const val MAX = 3

  /** What a surface opens: [key] identifies the thing (for de-duplication), [glyph] an approved icon, or null. */
  data class Target(val key: String, val title: String, val glyph: String?)

  data class Chip(val surface: String, val title: String, val why: String, val glyph: String?, val top: Boolean)

  fun build(ranked: List<ForYouRanker.Suggestion>, household: Boolean, resolve: (String) -> Target?): List<Chip> {
    val seen = mutableSetOf<String>()
    return ranked.mapNotNull { s -> resolve(s.surface)?.takeIf { seen.add(it.key) }?.let { s to it } }
        .take(MAX)
        .mapIndexed { i, (s, t) -> Chip(s.surface, t.title, why(t.key, household), t.glyph, top = i == 0) }
  }

  /** Hearthpane's own screens and a Jarvis conversation; apps are resolved against the installed list. */
  fun screenTarget(surface: String): Target? = when (surface) {
    ForYouRanker.SCREEN_MUSIC -> Target(MUSIC, "Music", "music")
    ForYouRanker.SCREEN_CALLS -> Target(CALLS, "Calls", "phone")
    MemoryHandoff.JARVIS_SURFACE -> Target(JARVIS, "Ask Jarvis", "jarvis")
    else -> null
  }

  fun heading(household: Boolean): String = if (household) "Popular here right now" else "For you right now"

  fun why(key: String, household: Boolean): String = when (key) {
    MUSIC -> if (household) "Often played here around now" else "You usually start music around now"
    CALLS -> if (household) "Calls often start here around now" else "You often call around now"
    JARVIS -> if (household) "Jarvis is often asked around now" else "You often ask Jarvis around now"
    else -> if (household) "Often opened here around now" else "You usually open it around now"
  }

  /** The why-sheet's sentence (mock `whySheet`). */
  fun explanation(chip: Chip, household: Boolean): String =
      "${chip.why}. Learned on this Portal from what " +
          (if (household) "is opened here" else "you open and ask about") + ". Nothing was typed in."

  const val MUSIC = "music"
  const val CALLS = "calls"
  const val JARVIS = "jarvis"
}

/**
 * The Learning switch on "What Jarvis knows" shows the real "Jarvis learns from conversations" setting (and this
 * person's pause), never the operator's [MemoryDebug] force. Turning it on with the setting off turns the setting on.
 * Pure.
 */
internal object KnowsLearning {
  data class Change(val turnSettingOn: Boolean, val paused: Boolean)

  fun checked(settingOn: Boolean, paused: Boolean): Boolean = settingOn && !paused

  fun change(on: Boolean, settingOn: Boolean): Change = Change(turnSettingOn = on && !settingOn, paused = !on)
}
