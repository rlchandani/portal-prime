/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/** A body paint as the art's vertical gradient (packed 0xRRGGBB, top = roof light, bottom = sill shade). */
@Immutable
internal data class CcPaint(val name: String, val top: Int, val bottom: Int)

/**
 * Tesla paint names (TeslaMate / Teslemetry `exterior_color`, e.g. "MidnightSilver", "Pearl White
 * Multi-Coat") mapped to tasteful gradients (pure, unit tested). Unknown or missing = a neutral
 * graphite that reads on both the dark and light cards.
 */
internal object CcTeslaPaint {
  val NEUTRAL = CcPaint("Graphite", 0x7A808A, 0x30343B)

  /** First match wins, so the more specific names ("Midnight Silver" before "silver") come first. */
  private val PAINTS = listOf(
      listOf("midnightsilver", "stealthgrey", "stealthgray", "grey", "gray") to CcPaint("Grey", 0x7A808A, 0x2A2E34),
      listOf("ultrared", "redmulticoat", "red") to CcPaint("Red", 0xC4323B, 0x4A0E13),
      listOf("quicksilver", "silvermetallic", "silver") to CcPaint("Quicksilver", 0xD3D7DD, 0x7C838C),
      listOf("pearlwhite", "white") to CcPaint("Pearl White", 0xF4F5F7, 0xB4BAC3),
      listOf("solidblack", "obsidian", "black") to CcPaint("Black", 0x4A4E55, 0x101114),
      listOf("deepblue", "blue") to CcPaint("Deep Blue", 0x4C6FA6, 0x14223B),
  )

  fun of(name: String?): CcPaint {
    val key = name?.lowercase()?.filter { it.isLetter() }?.takeIf { it.isNotEmpty() && it != "unknown" && it != "unavailable" } ?: return NEUTRAL
    if (key == "midnightcherryred") return CcPaint("Midnight Cherry", 0x7A1F2E, 0x2A0A10)
    return PAINTS.firstOrNull { (keys, _) -> keys.any { it in key } }?.second ?: NEUTRAL
  }
}
