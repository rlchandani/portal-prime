/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.Semaphore

/** Shared preview capacity policy; the lookup recomputes current file metadata. */
internal fun <T> approvedSourcesPreviewWithSlots(slots: Semaphore, cached: () -> T?, decode: () -> T?): T? {
    approvedSourcesCheckInterrupted()
    cached()?.let { approvedSourcesCheckInterrupted(); return it }
    slots.acquire()
    return try {
        approvedSourcesCheckInterrupted()
        // Refresh metadata and cache after a wait: another worker may have loaded the item.
        cached()?.let { approvedSourcesCheckInterrupted(); return it }
        decode()
    } finally { slots.release() }
}
