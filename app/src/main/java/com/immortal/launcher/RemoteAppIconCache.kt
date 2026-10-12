/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

internal data class RemoteAppIconKey(
    val ownerPackage: String,
    val packageName: String,
    val sizePx: Int,
    val resources: String,
)

/** Encoded bytes only: 32 entries of at most 64 KiB, with no retained Context or drawable. */
internal class RemoteAppIconCache<C : Any>(
    private val subscribe: (C, () -> Unit) -> Boolean,
    ttlMillis: Long = 30_000,
    maxEntries: Int = 32,
    private val maxRetainedBytes: Int = 64 * 1024,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Encoded(val bytes: ByteArray?, val current: Boolean)
    private val cache = SingleFlightValueCache<RemoteAppIconKey, Encoded>(
        ttlMillis, maxEntries, nowMillis,
        retainValue = { it.current && it.bytes != null &&
            it.bytes.isNotEmpty() && it.bytes.size <= maxRetainedBytes },
    )
    private val registrationLock = Any()
    @Volatile private var registered = false

    init { require(maxRetainedBytes > 0) }

    fun png(context: C, key: RemoteAppIconKey, canRetain: () -> Boolean = { true },
            loader: () -> ByteArray?): ByteArray? {
        if (!register(context)) return loader()?.copyOf()
        return cache.get(key) {
            val bytes = loader()?.copyOf()
            Encoded(bytes, runCatching { canRetain() }.getOrDefault(false))
        }.bytes?.copyOf()
    }

    private fun register(context: C): Boolean {
        if (registered) return true
        return synchronized(registrationLock) {
            if (!registered) registered = runCatching { subscribe(context, ::invalidate) }
                .getOrDefault(false)
            registered
        }
    }

    fun invalidate() { cache.clear() }
}
