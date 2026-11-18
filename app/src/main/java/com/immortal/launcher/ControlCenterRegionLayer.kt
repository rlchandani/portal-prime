/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * One large, mostly static region of the window (rail, header, side column) kept as a cached
 * GPU texture.
 *
 * Every Compose frame re-records the window's root display list, so the renderer redraws the
 * whole window, not just what changed. Inside an offscreen layer it redraws only the bounds of
 * the nodes that changed. So on a frame where the region didn't change (a sheet sliding, the plan
 * animating, a lens switch) it is one texture copy instead of a replay of its text, icons and
 * cards; a change inside it (a tapped row, a camera snapshot) re-renders just that part.
 * Measured on the Family Portal: a steady room-sheet frame went from 16 to 8 million RenderThread
 * cycles.
 *
 * The layer paints [background] — the colour already under the region — before its content, so
 * the texture is opaque and its pixels are exactly the ones drawing straight onto the window
 * gave (no extra blending pass at anti-aliased edges).
 */
internal fun Modifier.ccRegionLayer(background: Color): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).background(background)
