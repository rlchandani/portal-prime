/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.appwidget.AppWidgetHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
internal fun ApprovedHomeWidgetListening(host: AppWidgetHost, enabled: Boolean) {
  val owner = LocalLifecycleOwner.current
  DisposableEffect(host, owner, enabled) {
    val close = listenToHomeWidgets(host, owner.lifecycle, enabled)
    onDispose { close() }
  }
}

/** Own one request per visible interval; Compose cards do not use the AppWidget service. */
internal fun listenToHomeWidgets(host: AppWidgetHost, lifecycle: Lifecycle, enabled: Boolean): () -> Unit {
  if (!enabled) return {}
  var requested = false
  var closed = false
  fun synchronize() {
    if (closed) return
    val visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    if (visible == requested) return
    // Record ownership before calling the framework: start can partially register then throw.
    requested = visible
    if (visible) runCatching { host.startListening() }
    else runCatching { host.stopListening() }
  }
  val observer = LifecycleEventObserver { _, _ -> synchronize() }
  lifecycle.addObserver(observer)
  // addObserver replays the current state. This fallback is idempotent.
  synchronize()
  return {
    if (!closed) {
      closed = true
      lifecycle.removeObserver(observer)
      if (requested) {
        requested = false
        runCatching { host.stopListening() }
      }
    }
  }
}
