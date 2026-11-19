/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration

/**
 * The one responsive source of truth: which layout family the current window gets.
 *
 * Derived from the window's size in dp (never from the device model):
 *  - [LARGE]: the Portal+ canvas (1920×1219 dp, sw1280) and anything whose short side is at least
 *    [COMPACT_SHORT_SIDE_DP]. Every screen's original layout; LARGE is never changed by the compact work.
 *  - [COMPACT_PORTRAIT]: a short side below [COMPACT_SHORT_SIDE_DP] and taller than wide
 *    (the Portal Mini upright, 800×1220 dp).
 *  - [COMPACT_LANDSCAPE]: a short side below [COMPACT_SHORT_SIDE_DP] and wider than tall
 *    (the Mini turned, 1280×740 dp; also the Portal 10" class).
 *
 * A TV (the Portal TV's remote-driven UI) always stays [LARGE]: it is focus-navigated and was never
 * part of the compact work. An undefined size (0) is also [LARGE], the pre-existing behaviour.
 */
enum class LayoutClass {
  COMPACT_PORTRAIT,
  COMPACT_LANDSCAPE,
  LARGE;

  /** Either compact class (the Mini in any orientation). */
  val compact: Boolean get() = this != LARGE

  val compactPortrait: Boolean get() = this == COMPACT_PORTRAIT

  val compactLandscape: Boolean get() = this == COMPACT_LANDSCAPE

  companion object {
    /** A window whose short side is below this (dp) is compact. Mini: 740/800. Portal+: 1219/1280. */
    const val COMPACT_SHORT_SIDE_DP = 1000

    /**
     * Below this width (dp) a row of side-by-side panes no longer fits (the Mini upright, 800 dp):
     * the header's compact tier, single-pane Settings, stacked greeting.
     */
    const val NARROW_WIDTH_DP = 1000

    /** Pure classification from the window size in dp; see the class KDoc. */
    fun of(widthDp: Int, heightDp: Int, television: Boolean = false): LayoutClass {
      if (television || widthDp <= 0 || heightDp <= 0) return LARGE
      if (minOf(widthDp, heightDp) >= COMPACT_SHORT_SIDE_DP) return LARGE
      return if (heightDp > widthDp) COMPACT_PORTRAIT else COMPACT_LANDSCAPE
    }

    /** From an Android [Configuration] (`screenWidthDp`/`screenHeightDp` are the app window's). */
    fun of(configuration: Configuration): LayoutClass = of(
        configuration.screenWidthDp, configuration.screenHeightDp,
        television = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION)
  }
}

/**
 * Provided once at the theme root ([com.immortal.launcher.ui.theme.PortalPrimeTheme]). Null outside
 * it; read through [currentLayoutClass], which then derives it from [LocalConfiguration] itself.
 */
val LocalLayoutClass = staticCompositionLocalOf<LayoutClass?> { null }

/** The current window's [LayoutClass]; recomposes with the configuration (rotation relayouts). */
@Composable
@ReadOnlyComposable
fun currentLayoutClass(): LayoutClass = LocalLayoutClass.current ?: LayoutClass.of(LocalConfiguration.current)
