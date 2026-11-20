/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Shared boilerplate helpers — the patterns every widget copy-pasted (ON_RESUME re-reads,
 * polling fetch loops, safe activity launches, muted placeholder text) in one place.
 */

/**
 * Run [block] every time the host lifecycle resumes. Adding a lifecycle observer replays the
 * events up to the current state, so when the host is already resumed (any composition after
 * Activity start, e.g. a Home widget) [block] also runs once right after the first composition.
 * Home widgets rely on that catch-up read; screens that already load in `remember` should use
 * [OnResumeAfterFirstComposition] instead.
 */
@Composable
fun OnResume(block: () -> Unit) {
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val stop = observeResumes(lifecycleOwner.lifecycle, replayCurrent = true, block)
    onDispose { stop() }
  }
}

/**
 * Run [block] on each later resume only (returning from another Activity or the screen being
 * turned back on), never for the catch-up resume replayed while the observer is added. For
 * content that already read its values in `remember`, so a page is not loaded twice.
 */
@Composable
fun OnResumeAfterFirstComposition(block: () -> Unit) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val latest = rememberUpdatedState(block)
  DisposableEffect(lifecycleOwner) {
    val stop = observeResumes(lifecycleOwner.lifecycle, replayCurrent = false) { latest.value() }
    onDispose { stop() }
  }
}

/**
 * Calls [block] on ON_RESUME; returns the unsubscribe. With [replayCurrent] false the events the
 * registry replays synchronously inside `addObserver` (to catch up to the current state) are ignored.
 */
fun observeResumes(lifecycle: Lifecycle, replayCurrent: Boolean, block: () -> Unit): () -> Unit {
  var catchingUp = !replayCurrent
  val observer = LifecycleEventObserver { _, event ->
    if (event == Lifecycle.Event.ON_RESUME && !catchingUp) block()
  }
  lifecycle.addObserver(observer)
  catchingUp = false
  return { lifecycle.removeObserver(observer) }
}

/**
 * The widget polling loop: fetch on IO, then refresh every [refreshMs] — or retry after
 * [retryMs] while the result is null/empty. Restarts when any of [keys] changes.
 */
@Composable
fun <T> pollingState(
    vararg keys: Any?,
    refreshMs: Long = 30L * 60 * 1000,
    retryMs: Long = 60_000L,
    isEmpty: (T?) -> Boolean = { it == null || (it as? Collection<*>)?.isEmpty() == true },
    fetch: suspend () -> T?,
): State<T?> =
    produceState<T?>(initialValue = null, keys = keys) {
      while (true) {
        value = withContext(Dispatchers.IO) { fetch() }
        delay(if (isEmpty(value)) retryMs else refreshMs)
      }
    }

/** Start [cls] with NEW_TASK, swallowing failures (the standard tile/widget tap-through). */
fun Context.launchSafely(cls: Class<out Activity>) {
  runCatching { startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
