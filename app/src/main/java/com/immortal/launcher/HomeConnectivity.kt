/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home's header "Connected" check. `activeNetwork` is a binder call into system_server (1-6 ms on
 * Family per resume, more under contention), so it runs on [io], never the main thread.
 */
internal object HomeConnectivity {
  /** Interval between re-checks while Home is composed. */
  const val RECHECK_MS = 15_000L

  suspend fun isConnected(
      context: Context,
      io: CoroutineDispatcher = Dispatchers.IO,
      probe: (Context) -> Boolean = ::hasActiveNetwork,
  ): Boolean = withContext(io) { runCatching { probe(context) }.getOrDefault(false) }

  private fun hasActiveNetwork(context: Context): Boolean =
      (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork != null
}
