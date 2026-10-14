/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Blocking worker cache: share each key's active load; keep only bounded fresh successes. */
internal class SingleFlightValueCache<K : Any, V : Any>(
    private val ttlMillis: Long,
    private val maxEntries: Int,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val retainValue: (V) -> Boolean = { true },
) {
  private data class Cached<V>(val value: V, val completedAt: Long, val retain: Boolean)
  private val lock = Any()
  private val completed = LinkedHashMap<K, Cached<V>>(16, .75f, true)
  private val loading = HashMap<K, FutureTask<Cached<V>>>()

  init { require(ttlMillis > 0 && maxEntries > 0) }

  fun get(key: K, loader: () -> V): V = get(key, false, loader)

  /** Explicit refresh skips a completed value, while still joining the active load. */
  fun get(key: K, refresh: Boolean, loader: () -> V): V {
    val task = synchronized(lock) {
      completed[key]?.let { entry ->
        val age = nowMillis() - entry.completedAt
        if (!refresh && age >= 0 && age < ttlMillis) return entry.value
        completed.remove(key)
      }
      loading.getOrPut(key) { FutureTask {
        val value = loader()
        Cached(value, nowMillis(), retainValue(value))
      } }
    }
    // FutureTask claims execution once; other callers wait without holding the map lock.
    task.run()
    val entry = try { task.get() }
    catch (error: InterruptedException) {
      Thread.currentThread().interrupt()
      throw error
    }
    catch (error: ExecutionException) {
      synchronized(lock) { if (loading[key] === task) loading.remove(key) }
      throw (error.cause ?: error)
    }
    synchronized(lock) {
      // clear() may have detached this task and allowed a new owner for the same key.
      if (loading[key] === task) {
        loading.remove(key)
        if (entry.retain) {
          completed[key] = entry
          while (completed.size > maxEntries) completed.remove(completed.keys.first())
        }
      }
    }
    return entry.value
  }

  fun clear() = synchronized(lock) {
    completed.clear()
    loading.clear()
  }
}
