/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

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

    /**
     * Leaving full-screen Music, one step per frame. Once Home has gone inactive beneath Music, bringing it
     * back (recomposing and measuring Home), showing it (recording it again) and tearing Music down were one
     * 50–80 ms frame. Now Home first comes back beneath Music, which stays on screen and opaque, recording
     * into its hidden layers; the next frame swaps the two layers' alpha (no recomposition, no recording),
     * which is the frame the household sees; Music's composition is torn down, already invisible, in the
     * third. Bringing Home back can run past the next vsync, so the swap waits one frame callback
     * ([MusicCloseStep.SETTLE], which draws nothing) and starts on a fresh vsync instead of a late one.
     * A Music that closes before Home went inactive just goes: Home is ready.
     */
    fun closePlan(layout: MusicPlayerLayout?, covered: Boolean): List<MusicCloseStep> =
        if (layout == MusicPlayerLayout.FULL && covered)
            listOf(MusicCloseStep.UNCOVER_HOME, MusicCloseStep.SETTLE, MusicCloseStep.SHOW_HOME, MusicCloseStep.REMOVE_MUSIC)
        else listOf(MusicCloseStep.REMOVE_MUSIC)

    /**
     * Home's layers' alpha: 0 under full-screen Music until the closing step that shows it. A zero-alpha
     * layer is skipped by the renderer but keeps its recorded display list.
     */
    fun homeAlpha(layout: MusicPlayerLayout?, shownOverMusic: Boolean): Float =
        if (layout == MusicPlayerLayout.FULL && !shownOverMusic) 0f else 1f

    /** Full-screen Music's layer alpha: 0 for its last frame, once Home is shown, until it is removed. */
    fun musicAlpha(homeShown: Boolean): Float = if (homeShown) 0f else 1f

    /**
     * What Home's Now Playing card sees as "music open". While Music is closing it stays open: the card would
     * otherwise take the one player from Music's screen while Music still shows it.
     */
    fun cardSeesMusicOpen(homeLayout: MusicPlayerLayout?, closing: Boolean): Boolean = homeLayout != null || closing
}

/** One frame's step of leaving full-screen Music ([HomeMusicCover.closePlan]). */
internal enum class MusicCloseStep { UNCOVER_HOME, SETTLE, SHOW_HOME, REMOVE_MUSIC }

/**
 * Composes full-screen Music; the only composition that reads the live layout, so opening it recomposes only this.
 * [hidden] (read in its layer only) hides Music for the closing frame that shows Home ([HomeMusicCover.closePlan]).
 */
@Composable
internal fun MusicFullScreenSlot(layout: State<MusicPlayerLayout?>, hidden: State<Boolean>, content: @Composable () -> Unit) {
    if (layout.value == MusicPlayerLayout.FULL) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = HomeMusicCover.musicAlpha(hidden.value) }) { content() }
    }
}
