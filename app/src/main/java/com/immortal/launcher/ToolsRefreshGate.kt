/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Tracks successful manual refreshes; callers leave canceled or unusable results pending. */
internal class ToolsRefreshGate {
    private var completedRevision = 0

    fun isPending(revision: Int): Boolean {
        require(revision >= 0) { "Refresh revision must be nonnegative" }
        return revision > completedRevision
    }

    /** Call only after a successful, usable result has been delivered. */
    fun completed(revision: Int) {
        require(revision >= 0) { "Refresh revision must be nonnegative" }
        completedRevision = maxOf(completedRevision, revision)
    }
}
