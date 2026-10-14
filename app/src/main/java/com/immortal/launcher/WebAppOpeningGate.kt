/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Main-thread lifecycle gate for opening once after a committed frame while foregrounded. */
internal class WebAppOpeningGate {
    private var hasCommittedFrame = false
    private var started = false
    private var destroyed = false

    fun frameCommitted() {
        if (!destroyed) hasCommittedFrame = true
    }

    fun tryStart(foreground: Boolean): Boolean {
        if (destroyed || started || !hasCommittedFrame || !foreground) return false
        started = true
        return true
    }

    fun destroy() {
        destroyed = true
    }
}
