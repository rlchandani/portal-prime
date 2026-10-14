/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/** Android considers dreaming interactive; retain an observed dream when seeding the screen. */
internal fun initialPresenceScreen(interactive: Boolean?, fallback: ScreenState): ScreenState =
    when (interactive) {
      true -> if (fallback == ScreenState.DREAMING) ScreenState.DREAMING else ScreenState.INTERACTIVE
      false -> ScreenState.OFF
      null -> fallback
    }

/** Screen-on is not occupancy evidence; the runtime owner sets the transition timestamp. */
internal fun presenceAfterScreenOn(previous: PresenceState, confident: Boolean): PresenceState =
    previous.copy(
        presence = if (confident) previous.presence else Presence.UNKNOWN,
        screen = if (previous.screen == ScreenState.DREAMING) ScreenState.DREAMING else ScreenState.INTERACTIVE,
        confident = confident,
    )
