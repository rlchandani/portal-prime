/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context

object DigitalClockConfig {

  private const val PREFS = "digital_clock_config"

  const val COLOR_WHITE = "white"
  const val COLOR_RED = "red"
  const val COLOR_GREEN = "green"
  const val COLOR_BLUE = "blue"
  const val COLOR_YELLOW = "yellow"
  const val COLOR_CYAN = "cyan"
  const val COLOR_PINK = "pink"
  const val COLOR_ORANGE = "orange"

  const val FONT_LIGHT = "light"
  const val FONT_NORMAL = "normal"
  const val FONT_BOLD = "bold"
  const val FONT_MONO = "mono"
  const val FONT_SERIF = "serif"
  // Bundled OFL-licensed display fonts (SIL Open Font License 1.1 — freely
  // redistributable). Replacements chosen to match the visual intent of the
  // original personal-use-only fonts:
  //   DSEG7Classic-Regular  → digital_7.ttf   (7-segment LCD display)
  //   DSEG14Classic-Regular → segment_led.ttf (14-segment LED display)
  //   Orbitron-Regular      → technology.ttf  (geometric tech / LCD style)
  //   Orbitron-Bold         → technology_bold.ttf
  // Sources:
  //   DSEG:    https://github.com/keshikan/DSEG   (OFL-1.1)
  //   Orbitron: https://github.com/googlefonts/orbitron (OFL-1.1)
  const val FONT_SEGMENT_LED = "segment_led"   // expects segment_led.ttf
  const val FONT_DIGITAL_7 = "digital_7"       // expects digital_7.ttf
  const val FONT_TECHNOLOGY = "technology"     // expects technology.ttf

  const val SIZE_SMALL = "small"
  const val SIZE_MEDIUM = "medium"
  const val SIZE_LARGE = "large"
  const val SIZE_XL = "xl"

  // Layout positions
  const val LAYOUT_CENTER = "center"       // clock centered on screen
  const val LAYOUT_TOP = "top"             // clock near top
  const val LAYOUT_BOTTOM = "bottom"       // clock near bottom
  const val LAYOUT_MINIMAL = "minimal"     // clock only, no date, very large

  // Background styles
  const val BG_BLACK = "black"             // solid black
  const val BG_GRADIENT = "gradient"       // radial gradient
  const val BG_RED = "red"                 // solid dark red

  // Glow intensity
  const val GLOW_NONE = "none"
  const val GLOW_SOFT = "soft"
  const val GLOW_STRONG = "strong"

  // Clock styles. Classic is the only style that respects the user's
  // chosen font — the other styles use a built-in font and ignore
  // the font setting. Minimal and Outline were removed because they
  // didn't use the user's font and confused the font picker UX.
  const val STYLE_CLASSIC = "classic"       // Large time + date below, uses user font
  const val STYLE_FLIP = "flip"             // Retro flip clock with digit cards
  const val STYLE_BOLD = "bold"             // Extra bold, thick numbers
  const val STYLE_NEON = "neon"             // Bright neon glow
  const val STYLE_SEGMENT = "segment"       // 7-segment display style
  const val STYLE_ANALOG = "analog"         // Analog clock face

  data class Settings(
      val enabled: Boolean = false,
      val color: String = COLOR_WHITE,
      val font: String = FONT_LIGHT,
      val size: String = SIZE_LARGE,
      val layout: String = LAYOUT_CENTER,
      val background: String = BG_BLACK,
      val showDate: Boolean = true,
      val showSeconds: Boolean = false,
      val glow: String = GLOW_SOFT,
      val style: String = STYLE_CLASSIC,
  )

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(context: Context): Settings {
    val p = prefs(context)
    return Settings(
        enabled = p.getBoolean("enabled", false),
        color = p.getString("color", COLOR_WHITE) ?: COLOR_WHITE,
        font = p.getString("font", FONT_LIGHT) ?: FONT_LIGHT,
        size = p.getString("size", SIZE_LARGE) ?: SIZE_LARGE,
        layout = p.getString("layout", LAYOUT_CENTER) ?: LAYOUT_CENTER,
        background = p.getString("background", BG_BLACK) ?: BG_BLACK,
        showDate = p.getBoolean("show_date", true),
        showSeconds = p.getBoolean("show_seconds", false),
        glow = p.getString("glow", GLOW_SOFT) ?: GLOW_SOFT,
        style = p.getString("style", STYLE_CLASSIC) ?: STYLE_CLASSIC,
    )
  }

  fun setEnabled(c: Context, enabled: Boolean) =
      prefs(c).edit().putBoolean("enabled", enabled).apply()

  fun setColor(c: Context, color: String) =
      prefs(c).edit().putString("color", color).apply()

  fun setFont(c: Context, font: String) =
      prefs(c).edit().putString("font", font).apply()

  fun setSize(c: Context, size: String) =
      prefs(c).edit().putString("size", size).apply()

  fun setLayout(c: Context, layout: String) =
      prefs(c).edit().putString("layout", layout).apply()

  fun setBackground(c: Context, bg: String) =
      prefs(c).edit().putString("background", bg).apply()

  fun setShowDate(c: Context, show: Boolean) =
      prefs(c).edit().putBoolean("show_date", show).apply()

  fun setShowSeconds(c: Context, show: Boolean) =
      prefs(c).edit().putBoolean("show_seconds", show).apply()

  fun setGlow(c: Context, glow: String) =
      prefs(c).edit().putString("glow", glow).apply()

  fun setStyle(c: Context, style: String) =
      prefs(c).edit().putString("style", style).apply()
}
