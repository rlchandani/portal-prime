/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.util.concurrent.CompletableFuture

/** Coalesce readers on the existing serial decoder; closing releases waiters, not owned pixels. */
internal class EngineImageRequests(
    private val submit: (String, (() -> Boolean) -> Unit) -> Boolean,
) : AutoCloseable {
  private val lock = Any()
  private val pending = HashMap<String, CompletableFuture<File?>>()
  private var closed = false

  fun request(key: String, prepare: (() -> Boolean) -> File?): CompletableFuture<File?> =
      synchronized(lock) {
        if (closed) return@synchronized CompletableFuture.completedFuture(null)
        pending[key]?.let { return@synchronized it }
        val result = CompletableFuture<File?>()
        pending[key] = result
        val accepted = runCatching {
          submit("load:$key") { current ->
            try {
              val allowed = current() && synchronized(lock) { !closed }
              val file = if (allowed) runCatching { prepare(current) }.getOrNull() else null
              synchronized(lock) { result.complete(if (!closed && current()) file else null) }
            } finally {
              synchronized(lock) { if (pending[key] === result) pending.remove(key) }
            }
          }
        }.getOrDefault(false)
        if (!accepted) {
          pending.remove(key)
          result.complete(null)
        }
        result
      }

  override fun close() = synchronized(lock) {
    closed = true
    pending.values.forEach { it.complete(null) }
    pending.clear()
  }
}
