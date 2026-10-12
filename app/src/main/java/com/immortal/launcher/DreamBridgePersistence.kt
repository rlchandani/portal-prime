/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Marks and clears share one FIFO disk lane; only completion delivery touches Main. */
internal object DreamBridgePersistence {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Immortal-bridge-io").apply { isDaemon = true }
    }
    private val queue = DreamBridgeWriteQueue(
        enqueue = { work -> worker.execute { work() } },
        deliver = { reply -> Handler(Looper.getMainLooper()).post { reply() } },
    )
    fun submit(write: () -> Boolean, completed: ((Boolean) -> Unit)? = null) = queue.submit(write, completed)
    /** Existing synchronous Meta handoffs retain their durability and return contract. */
    fun commit(write: () -> Boolean): Boolean = runCatching { worker.submit(Callable(write)).get() }.getOrDefault(false)
}
