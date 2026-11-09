/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Type and control sizes of the full-screen Music stage (dp / sp), from the approved mock. Every
 * control is at least [MusicStageGeometry.MIN_TARGET]; the large Portal aims for 80.
 */
internal data class MusicStageDims(
    val title: Float,
    val artist: Float,
    val body: Float,
    val small: Float,
    val heading: Float,
    val bigTitle: Float,
    val play: Float,
    val playGlyph: Float,
    val skip: Float,
    val skipGlyph: Float,
    val side: Float,
    val sideGlyph: Float,
    val target: Float,
    val row: Float,
    val thumb: Float,
    val tile: Float,
    val idleTile: Float,
    val searchField: Float,
    val tab: Float,
    val tabText: Float,
    val transportGap: Float,
) {
  /** The header strip's smaller transport (scaled like the mock's `.sCtl`). */
  val stripSkip: Float get() = skip * STRIP_SKIP
  val stripPlay: Float get() = play * STRIP_PLAY

  /** Every tappable size this stage uses, for the touch-target floor test. */
  val targets: List<Float> get() = listOf(play, skip, side, target, row, searchField, tab, stripSkip, stripPlay)

  companion object {
    private const val STRIP_SKIP = 0.8f
    private const val STRIP_PLAY = 0.75f

    val LARGE = MusicStageDims(title = 84f, artist = 42f, body = 28f, small = 23f, heading = 40f, bigTitle = 68f,
        play = 160f, playGlyph = 116f, skip = 120f, skipGlyph = 84f, side = 88f, sideGlyph = 40f, target = 80f,
        row = 112f, thumb = 84f, tile = 208f, idleTile = 220f, searchField = 104f, tab = 84f, tabText = 25f,
        transportGap = 56f)
    val COMPACT = MusicStageDims(title = 52f, artist = 28f, body = 22f, small = 18f, heading = 30f, bigTitle = 46f,
        play = 104f, playGlyph = 76f, skip = 84f, skipGlyph = 58f, side = 72f, sideGlyph = 32f, target = 68f,
        row = 84f, thumb = 60f, tile = 150f, idleTile = 132f, searchField = 80f, tab = 68f, tabText = 19f,
        transportGap = 34f)

    fun of(spec: MusicStageSpec): MusicStageDims = if (spec.compact) COMPACT else LARGE
  }
}
