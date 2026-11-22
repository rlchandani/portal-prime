/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ComponentCallbacks2
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Settings stays alive between opens (owner decision, 2026-11-21). Back at the top of Settings moves its
 * own task behind Home instead of finishing, so the next open is a resume of the composed tree rather
 * than a cold activity launch and composition. Pure policy; [ImmortalSettingsActivity] owns the effects.
 *
 * - The first open after process start is the same cold launch as before.
 * - A parked Settings is released (finished) after [IDLE_RELEASE_MS] in the background or on memory
 *   pressure, so the next open is cold again. Nothing here holds an Activity.
 */
internal object SettingsRetention {
    /** A parked Settings is finished after this long unopened. */
    const val IDLE_RELEASE_MS = 10 * 60 * 1000L

    /** How Settings leaves on Back at its top. */
    enum class Exit { PARK, FINISH }

    /** What a reopen must reset before its first frame. */
    enum class Reopen {
        /** Not a reopen (a deep link while Settings is on screen): navigate only. */
        NONE,
        /** Left by Back at its top: only navigation, search, dialogs and scroll need resetting. */
        LIGHT,
        /** Left some other way (Home press, a sub-screen, the screensaver): compose afresh. */
        FULL,
    }

    /** Back at the top parks Settings unless it's already releasing. */
    fun exitOnBack(finishing: Boolean, retentionEnabled: Boolean): Exit =
        if (finishing || !retentionEnabled) Exit.FINISH else Exit.PARK

    /**
     * A new launch reaching an existing Settings. [leftTask] is true when neither Settings nor any of its
     * sub-screens is started: false for a deep link while Settings is on screen, and for a sub-screen that
     * navigates back into Settings (both just navigate, as before). [parked] is true only when the last exit
     * was Back at the top.
     */
    fun reopen(leftTask: Boolean, parked: Boolean): Reopen = when {
        !leftTask -> Reopen.NONE
        parked -> Reopen.LIGHT
        else -> Reopen.FULL
    }

    /**
     * Release on real memory pressure while not on screen. [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN]
     * alone is not pressure (it fires whenever another app covers ours) and the moderate level keeps it.
     */
    @Suppress("DEPRECATION")
    fun releaseOnTrim(level: Int, visible: Boolean): Boolean {
        if (visible) return false
        return when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> true
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> false
            else -> level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        }
    }

    /** Only an off-screen Settings with none of its sub-screens on screen (they share its task) is released. */
    fun releasable(settingsVisible: Boolean, startedSubScreens: Int): Boolean = !settingsVisible && startedSubScreens == 0

    /** Idle release: true once a parked Settings has been unopened for [idleMs]. */
    fun releaseWhenIdle(stoppedAtMs: Long?, nowMs: Long, idleMs: Long = IDLE_RELEASE_MS): Boolean =
        stoppedAtMs != null && nowMs - stoppedAtMs >= idleMs
}

/**
 * Per-Settings-activity registry of view-state resets (scroll positions) that a light reopen runs before
 * its first frame. Entries are registered and removed by the composables that own the state, so the
 * registry never outlives its activity and holds nothing static.
 */
internal class SettingsReopenResets {
    private val resets = LinkedHashMap<Any, () -> Unit>()

    fun register(owner: Any, reset: () -> Unit) { resets[owner] = reset }
    fun unregister(owner: Any) { resets.remove(owner) }
    val size: Int get() = resets.size

    /** Runs every reset; one failing reset never blocks the rest. */
    fun runAll(): Int {
        var failed = 0
        for (reset in resets.values.toList()) {
            if (runCatching(reset).isFailure) failed++
        }
        return failed
    }
}

internal val LocalSettingsReopenResets = staticCompositionLocalOf<SettingsReopenResets?> { null }

/**
 * Bumped on every light reopen; transient shell state (search, dialogs) is keyed on it. Dynamic, so a bump
 * recomposes only its readers, never the pages.
 */
internal val LocalSettingsReopenGeneration = compositionLocalOf { 0 }

/**
 * Registers [reset] with the activity's [SettingsReopenResets] for as long as the caller is composed. Outside
 * Settings (no registry) it does nothing.
 */
@Composable
internal fun SettingsReopenReset(owner: Any, reset: () -> Unit) {
    val resets = LocalSettingsReopenResets.current ?: return
    val current by rememberUpdatedState(reset)
    DisposableEffect(resets, owner) {
        resets.register(owner) { current() }
        onDispose { resets.unregister(owner) }
    }
}

/** A light reopen shows a list from its top, as a cold open does. */
@Composable
internal fun SettingsReopenScrollsToTop(state: LazyListState) =
    SettingsReopenReset(state) { state.requestScrollToItem(0, 0) }

@Composable
internal fun SettingsReopenScrollsToTop(state: ScrollState) =
    SettingsReopenReset(state) { if (state.value != 0) state.dispatchRawDelta(-state.value.toFloat()) }
