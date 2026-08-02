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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Shared boilerplate helpers — the patterns every widget copy-pasted (ON_RESUME re-reads,
 * polling fetch loops, safe activity launches, muted placeholder text) in one place.
 */

/** Run [block] every time the host lifecycle resumes (initial composition does NOT invoke it). */
@Composable
fun OnResume(block: () -> Unit) {
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) block() }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }
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

/** The muted empty/loading/connect placeholder line every widget shows. */
@Composable
fun WidgetPlaceholder(text: String) {
  Text(text, color = Glass.TextMuted, fontSize = 14.sp)
}
