/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Main-owned preparation state; a cancelled or superseded request can never launch later. */
internal class JarvisPreparedLaunch(
    private val current: () -> Boolean,
    private val dispatch: () -> Boolean,
    private val succeeded: () -> Unit,
    private val failed: () -> Unit,
    private val superseded: () -> Unit = {},
) {
    private var pending = true
    var launched = false
        private set
    fun cancel() { pending = false }
    fun prepared(committed: Boolean) {
        if (!pending) return
        pending = false
        if (!current()) { superseded(); return }
        if (committed && runCatching(dispatch).getOrDefault(false)) {
            launched = true
            succeeded()
        } else failed()
    }
}
