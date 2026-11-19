/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/**
 * What Home itself sees of the music layout. Full-screen Music is opaque and Home is not drawn under it,
 * so Home's chrome (header buttons, the Now Playing card, its tab pages going inactive) need not react in
 * the frame Music opens: that recomposition and relayout of all of Home was most of the opening frame
 * (Portal Mini 70–90 ms). Home goes inactive [SETTLE_MS] later, once Music has settled, and comes back
 * at once when Music closes. Inline layouts (compact, photos) show over Home and apply immediately. Pure.
 */
internal object HomeMusicCover {
    /**
     * After Music's own opening work (stage, list, the player attaching and, signed out, the sign-in code
     * arriving at about 1 s), so Home's one recomposition never lands in a frame Music needs.
     */
    const val SETTLE_MS = 3_000L

    fun homeLayout(layout: MusicPlayerLayout?, covered: Boolean): MusicPlayerLayout? =
        if (layout == MusicPlayerLayout.FULL && !covered) null else layout
}

/** Composes full-screen Music; the only composition that reads the live layout, so opening it recomposes only this. */
@Composable
internal fun MusicFullScreenSlot(layout: State<MusicPlayerLayout?>, content: @Composable () -> Unit) {
    if (layout.value == MusicPlayerLayout.FULL) content()
}
