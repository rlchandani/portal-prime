/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** One bounded configuration epoch; values are immutable icon data, never UI owners. */
internal class SettingsIconCache<K : Any, V : Any>(
    allowedIds: Set<Int>,
    private val snapshot: (K) -> K,
) {
    private val allowed = allowedIds.toSet()
    private var configurationSnapshot: K? = null
    private val values = HashMap<Int, V>(allowed.size)

    @Synchronized
    fun get(configuration: K, id: Int, load: (Int) -> V): V {
        require(id in allowed)
        if (configurationSnapshot != configuration) {
            configurationSnapshot = snapshot(configuration)
            values.clear()
        }
        return values[id] ?: load(id).also { values[id] = it }
    }
}
