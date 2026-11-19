/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * How much of Settings is composed while it opens. Opening used to build the whole split view in
 * one ~150 ms frame (Portal Mini and Portal+). Now the first frame is only the shell's backdrops,
 * the master column starts on the next frame (built over a few, see [SettingsMasterReveal]) and the
 * page follows once it is shown; from then on (page switches, search) everything stays composed.
 */
internal enum class SettingsOpenStage {
    SKELETON, MASTER, COMPLETE;

    val showsMaster: Boolean get() = this != SKELETON
    val showsDetail: Boolean get() = this == COMPLETE

    /** The next stage, or null once complete. */
    fun next(): SettingsOpenStage? = entries.getOrNull(ordinal + 1)
}

/**
 * Advances one stage per frame from the first composition, then stays [SettingsOpenStage.COMPLETE].
 * The page waits while [holdDetail] is true (the master column is still being built), so the two
 * never share a frame's budget.
 */
@Composable
internal fun rememberSettingsOpenStage(holdDetail: () -> Boolean = { false }): SettingsOpenStage {
    var stage by remember { mutableStateOf(SettingsOpenStage.SKELETON) }
    val hold by rememberUpdatedState(holdDetail)
    LaunchedEffect(Unit) {
        while (true) {
            val next = stage.next() ?: break
            withFrameNanos { }
            if (next.showsDetail) while (hold()) withFrameNanos { }
            stage = next
        }
    }
    return stage
}
