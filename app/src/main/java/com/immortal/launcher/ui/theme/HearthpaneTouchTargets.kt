/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Hearthpane's touch-target floor: 64 dp, above Android's 48 dp default, for a wall-mounted
 * screen used at arm's length (docs/design/portal-plus-quality-gates.md, D5).
 */
object HearthpaneTouchTargets {
  const val MINIMUM_DP = 64f

  /**
   * The minimum in the dp of a subtree whose density was rescaled (Control Center's compact 75%
   * scale, the Mini's widget cards), so the floor stays 64 dp of the real screen.
   */
  fun minimumDp(windowDensity: Float, localDensity: Float): Float =
      if (windowDensity <= 0f || localDensity <= 0f) MINIMUM_DP else MINIMUM_DP * windowDensity / localDensity
}

/** The platform configuration with only the minimum touch target raised. */
private class HearthpaneViewConfiguration(val base: ViewConfiguration, minimumDp: Float) : ViewConfiguration by base {
  override val minimumTouchTargetSize: DpSize = DpSize(minimumDp.dp, minimumDp.dp)
}

/** The window's own density, captured by the outermost provider. */
private val LocalTouchTargetWindowDensity = staticCompositionLocalOf<Float?> { null }

/**
 * Extends the hit area (and the reported accessibility bounds) of every clickable smaller than
 * 64 dp to 64 dp, without changing layout or drawing. Compose prefers a direct hit, so an expanded
 * area never steals a touch that lands on a neighbour.
 *
 * Each window resets this local (every ComposeView, Dialog and Popup provides its own
 * ViewConfiguration), so every composition root wraps its content in this, directly or through
 * [PortalPrimeTheme]. A subtree that rescales LocalDensity wraps again inside the new density.
 * `AccessibilitySourceScanTest` checks the roots.
 */
@Composable
fun ProvideHearthpaneTouchTargets(content: @Composable () -> Unit) {
  val local = LocalDensity.current.density
  val window = LocalTouchTargetWindowDensity.current ?: local
  val current = LocalViewConfiguration.current
  val base = (current as? HearthpaneViewConfiguration)?.base ?: current
  val minimum = HearthpaneTouchTargets.minimumDp(window, local)
  val raised = remember(base, minimum) { HearthpaneViewConfiguration(base, minimum) }
  CompositionLocalProvider(LocalViewConfiguration provides raised,
      LocalTouchTargetWindowDensity provides window, content = content)
}
