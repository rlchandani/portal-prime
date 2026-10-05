/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Main-thread request identity. Leaving a form permanently rejects even an already queued reply. */
internal class ApprovedSourcesOperation {
    private var generation = 0L
    private var closed = false

    fun begin(): Long = ++generation
    fun accepts(request: Long): Boolean = !closed && request == generation
    fun cancel() { generation++ }
    fun close() { closed = true; cancel() }
}
