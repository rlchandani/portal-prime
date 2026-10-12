/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Durable disk work precedes completion delivery; production uses one ordered worker. */
internal class DreamBridgeWriteQueue(
    private val enqueue: (() -> Unit) -> Unit,
    private val deliver: (() -> Unit) -> Unit,
) {
    fun submit(write: () -> Boolean, completed: ((Boolean) -> Unit)? = null) {
        enqueue {
            val committed = runCatching(write).getOrDefault(false)
            if (completed != null) deliver { completed(committed) }
        }
    }
}
