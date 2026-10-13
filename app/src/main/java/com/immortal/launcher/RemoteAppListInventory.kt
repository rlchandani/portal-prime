/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Collections

internal data class RemoteAppListEntry(val packageName: String, val label: String)
internal data class RemoteAppListKey(val owner: String, val resources: String, val device: String, val locale: String)
internal data class RemoteAppListScan(val entries: List<RemoteAppListEntry>, val complete: Boolean = true)

/** Worker-only, bounded primitive snapshots. No Context, JSON, Drawable or timer is retained. */
internal class RemoteAppListInventory<C : Any>(
    private val subscribe: (C, () -> Unit) -> Boolean,
    ttlMillis: Long = 5_000,
    maxEntries: Int = 4,
    private val maxRetainedApps: Int = 512,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
  private val registrationLock = Any()
  @Volatile private var registered = false
  private val cache = SingleFlightValueCache<RemoteAppListKey, RemoteAppListScan>(
      ttlMillis, maxEntries, nowMillis,
      retainValue = { it.complete && it.entries.size <= maxRetainedApps },
  )
  init { require(maxRetainedApps > 0) }

  fun entries(context: C, key: RemoteAppListKey, canRetain: () -> Boolean = { true },
      loader: () -> RemoteAppListScan): List<RemoteAppListEntry> {
    fun load(): RemoteAppListScan {
      val scanned = loader()
      val current = runCatching(canRetain).getOrDefault(false)
      return RemoteAppListScan(Collections.unmodifiableList(ArrayList(scanned.entries)), scanned.complete && current)
    }
    if (!register(context)) return load().entries
    return cache.get(key, ::load).entries
  }

  private fun register(context: C): Boolean {
    if (registered) return true
    return synchronized(registrationLock) {
      if (!registered) registered = runCatching { subscribe(context, ::invalidate) }.getOrDefault(false)
      registered
    }
  }
  fun invalidate() { cache.clear() }
}
