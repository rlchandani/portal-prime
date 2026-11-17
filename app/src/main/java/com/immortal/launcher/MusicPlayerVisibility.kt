/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Whether Music's YouTube player is on screen: its surface is composed ([hosted]) AND the screen
 * hosting it is in the foreground ([foreground], lifecycle RESUMED). Pure; driven by
 * [YouTubeMusicEngine].
 *
 * Leaving composition (Back, Home) and leaving the foreground while still composed (another app,
 * the screensaver) are both "hidden", so either starts the release grace; becoming visible again
 * cancels it and keeps (or, once released, rebuilds) the player.
 */
internal data class MusicPlayerVisibility(val hosted: Boolean = false, val foreground: Boolean = false) {
  val visible: Boolean get() = hosted && foreground

  fun withHosted(on: Boolean) = copy(hosted = on)
  fun withForeground(on: Boolean) = copy(foreground = on)

  /** What the engine does when visibility changes from this to [next]. */
  fun effectTo(next: MusicPlayerVisibility): PlayerVisibilityEffect = when {
    !visible && next.visible -> PlayerVisibilityEffect.SHOW
    visible && !next.visible -> PlayerVisibilityEffect.START_GRACE
    else -> PlayerVisibilityEffect.NONE
  }
}

internal enum class PlayerVisibilityEffect {
  NONE,
  /** Cancel a pending release and host the player (creating it if it was released). */
  SHOW,
  /** Release the player after [YouTubeMusicEngine.RELEASE_GRACE_MS] unless it is shown again. */
  START_GRACE,
}
