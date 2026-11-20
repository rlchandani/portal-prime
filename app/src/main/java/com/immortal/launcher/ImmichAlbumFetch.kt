/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The phone's Immich album picker fetch, bounded: one fetch at a time, at most [MAX_BYTES] of
 * reply, and [BUDGET_MS] in total (the per-read timeout alone lets a trickling server hold a
 * Fleet thread for minutes). Redirects stay on the credential's origin ([PhotoSourceRedirects]).
 */
internal object ImmichAlbumFetch {
  const val BUDGET_MS = 20_000L
  const val MAX_BYTES = 4 * 1024 * 1024

  sealed interface Result {
    data class Albums(val albums: List<ImmichSource.Album>) : Result
    data object Failed : Result
    data object TimedOut : Result
  }

  private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, "immich-albums").apply { isDaemon = true }
  }

  fun list(
      url: String,
      key: String,
      budgetMs: Long = BUDGET_MS,
      fetch: (String, String) -> List<ImmichSource.Album>? = { u, k -> ImmichSource.listAlbums(u, k, MAX_BYTES) },
  ): Result {
    val task = try { worker.submit<List<ImmichSource.Album>?> { fetch(url, key) } } catch (_: RejectedExecutionException) {
      return Result.Failed
    }
    return try {
      task.get(budgetMs, TimeUnit.MILLISECONDS)?.let(Result::Albums) ?: Result.Failed
    } catch (_: TimeoutException) {
      task.cancel(true) // interrupts the fetch; its checkpoints and read timeout end it
      Result.TimedOut
    } catch (_: ExecutionException) {
      Result.Failed
    } catch (_: InterruptedException) {
      task.cancel(true)
      Thread.currentThread().interrupt()
      Result.Failed
    }
  }
}
