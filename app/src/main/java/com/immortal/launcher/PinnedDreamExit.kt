/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Preserve the Dream itself when an expired session has no attached replacement barrier. */
internal fun exitPinnedDream(
    pinnedOrigin: Boolean,
    pinnedNow: Boolean,
    admitPinnedReturn: () -> Boolean,
    showLostSession: () -> Unit,
    launchConfiguredApp: () -> Unit,
    finishDream: () -> Unit,
    retireContinuation: () -> Unit = {},
) {
    if (pinnedOrigin || pinnedNow) {
        if (!admitPinnedReturn()) {
            showLostSession()
            return
        }
    } else {
        retireContinuation()
        launchConfiguredApp()
    }
    finishDream()
}
