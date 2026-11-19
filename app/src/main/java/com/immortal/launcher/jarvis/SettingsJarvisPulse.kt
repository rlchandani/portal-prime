/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.SettingsBlock
import com.immortal.launcher.claimsControl

/**
 * Where the ember ring goes when Jarvis points at a Settings control that a bespoke block draws (Night Shift,
 * Brightness, Touch sounds…), not a registry row. The Display editor rings the card that holds the control; every
 * other block rings itself. Pure, so the mapping is JVM-tested.
 */
internal object SettingsJarvisPulse {
  /** The Display editor's cards, each ringed on its own. */
  enum class DisplayCard { BRIGHTNESS, VOLUME, NIGHT_SHIFT }

  /** Blocks drawn by the Display editor: their cards carry the ring, so the block itself doesn't. */
  private val PART_PULSED = setOf(SettingsBlock.DISPLAY, SettingsBlock.MEDIA_VOLUME)

  private val DISPLAY_CARDS = mapOf(
      "display.brightness" to DisplayCard.BRIGHTNESS,
      "display.auto" to DisplayCard.BRIGHTNESS,
      "display.mediaVolume" to DisplayCard.VOLUME,
      "display.nightShift" to DisplayCard.NIGHT_SHIFT,
      "display.from" to DisplayCard.NIGHT_SHIFT,
      "display.until" to DisplayCard.NIGHT_SHIFT,
      "display.warmth" to DisplayCard.NIGHT_SHIFT,
  )

  /** Whether [block] draws the control Jarvis points at ([pulse], a control id) and rings itself for it. */
  fun blockPulses(block: SettingsBlock, pulse: String?): Boolean =
      pulse != null && block !in PART_PULSED && owns(block, pulse)

  /** The Display editor card that holds [pulse], or null. */
  fun displayCard(pulse: String?): DisplayCard? = pulse?.let(DISPLAY_CARDS::get)

  private fun owns(block: SettingsBlock, control: String): Boolean =
      block.rows.any { it.id == control } || block.claimsControl(control)
}
