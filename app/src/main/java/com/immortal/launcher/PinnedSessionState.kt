/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** A live accessibility window identity; never a persisted/reusable Android task id. */
internal data class PinnedWindowIdentity(val packageName: String, val windowId: Int)

/** Main-thread session policy. Missing occluded windows are not proof of app destruction. */
internal class PinnedSessionState {
    @Volatile var target: PinnedWindowIdentity? = null
        private set
    @Volatile var lost: Boolean = false
        private set

    fun pin(window: PinnedWindowIdentity): Boolean {
        if (window.packageName.isBlank() || window.windowId < 0) return false
        target = window
        lost = false
        return true
    }

    fun observe(windows: List<PinnedWindowIdentity>, foreground: PinnedWindowIdentity?, covered: Boolean) {
        val saved = target ?: return
        if (!covered && foreground != null && foreground.packageName != saved.packageName) {
            clear()
            return
        }
        if (saved !in windows && windows.any { it.packageName == saved.packageName }) {
            if (covered) lost = true else clear()
        }
    }

    fun canReveal(windows: List<PinnedWindowIdentity>): Boolean {
        val saved = target ?: return false
        if (lost) return false
        if (saved !in windows) {
            lost = true
            return false
        }
        return true
    }

    fun clear() { target = null; lost = false }
}
