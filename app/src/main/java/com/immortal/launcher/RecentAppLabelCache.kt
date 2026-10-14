/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

internal data class RecentAppLabelKey(
    val packageName: String,
    val packageRevision: Long,
    val configuration: String,
)

/** Bounded raw strings only; loaders and Android resources are never retained. */
internal class RecentAppLabelCache(private val maxEntries: Int = 64) {
    private data class Owner(val packageRevision: Long, val configuration: String)
    private val lock = Any()
    private val labels = LinkedHashMap<RecentAppLabelKey, String>(16, 0.75f, true)
    private var owner: Owner? = null
    private var generation = 0L

    init { require(maxEntries > 0) }

    fun read(key: RecentAppLabelKey, loader: () -> String): String {
        val loadingGeneration = synchronized(lock) {
            val requestedOwner = Owner(key.packageRevision, key.configuration)
            if (owner != requestedOwner) {
                labels.clear()
                owner = requestedOwner
                generation++
            }
            labels[key]?.let { return it }
            generation
        }
        // Slow reads and failures happen outside the map lock. A failure remains retryable.
        val label = loader()
        synchronized(lock) {
            // Comparing generations also rejects an old -> new -> old owner transition.
            if (generation == loadingGeneration) {
                labels[key] = label
                while (labels.size > maxEntries) {
                    val oldest = labels.entries.iterator()
                    oldest.next()
                    oldest.remove()
                }
            }
        }
        return label
    }
}
