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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * How much of Settings is composed while it opens. Opening used to build the whole split view in
 * one ~150 ms frame (Portal Mini and Portal+). Now the first frame is only the shell's backdrops,
 * the master column composes on the next frame and the page on the one after; from then on (page
 * switches, search) everything stays composed.
 */
internal enum class SettingsOpenStage {
    SKELETON, MASTER, COMPLETE;

    val showsMaster: Boolean get() = this != SKELETON
    val showsDetail: Boolean get() = this == COMPLETE

    /** The next stage, or null once complete. */
    fun next(): SettingsOpenStage? = entries.getOrNull(ordinal + 1)
}

/** Advances one stage per frame from the first composition, then stays [SettingsOpenStage.COMPLETE]. */
@Composable
internal fun rememberSettingsOpenStage(): SettingsOpenStage {
    var stage by remember { mutableStateOf(SettingsOpenStage.SKELETON) }
    LaunchedEffect(Unit) {
        while (true) {
            val next = stage.next() ?: break
            withFrameNanos { }
            stage = next
        }
    }
    return stage
}
