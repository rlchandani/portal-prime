/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** A one-shot gate: foreground launch must succeed and cover its caller before mic activation. */
internal class JarvisForegroundWake(private val wake: () -> Unit) {
  private var launched = false
  private var stopped = false
  private var completed = false

  fun onLaunched() { launched = true; dispatch() }
  fun onHostStopped() { stopped = true; dispatch() }
  fun cancel() { completed = true }

  private fun dispatch() {
    if (!completed && launched && stopped) {
      completed = true
      wake()
    }
  }
}

/** Jarvis's exported wake contract; no dependency on, or configuration changes to, the assistant. */
internal object JarvisHandoff {
  const val PACKAGE = "com.portal.assistant"
  private const val WAKE_ACTION = "com.portal.wake.action.WAKE"
  private const val WAKE_ID = "com.portal.wake.extra.ID"
  // UI-thread only. Rapid taps on the same launching Activity share its pending handoff.
  private val pendingHosts = mutableSetOf<LifecycleOwner>()

  fun launch(context: Context): Boolean {
    val owner = generateSequence(context) { current ->
      (current as? ContextWrapper)?.baseContext?.takeUnless { it === current }
    }.filterIsInstance<LifecycleOwner>().firstOrNull()
    val lifecycle = owner?.lifecycle
    // UI callers have a started Activity. A non-UI caller can open Jarvis, but must not start
    // a potentially background microphone without a foreground handoff to observe.
    val canObserve = lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
    if (canObserve && owner != null && !pendingHosts.add(owner)) return true
    lateinit var observer: LifecycleEventObserver
    val gate = JarvisForegroundWake {
      lifecycle?.removeObserver(observer)
      owner?.let { pendingHosts.remove(it) }
      runCatching {
        context.sendBroadcast(
            Intent(WAKE_ACTION)
                .setClassName(PACKAGE, "$PACKAGE.wake.WakeHandoffReceiver")
                .putExtra(WAKE_ID, "immortal-tap"))
      }
    }
    observer = LifecycleEventObserver { _, event ->
      when (event) {
        // Android resumes the covering Activity before stopping the previous one.
        Lifecycle.Event.ON_STOP -> gate.onHostStopped()
        Lifecycle.Event.ON_DESTROY -> {
          gate.cancel()
          lifecycle?.removeObserver(observer)
          owner?.let { pendingHosts.remove(it) }
        }
        else -> Unit
      }
    }
    if (canObserve) lifecycle?.addObserver(observer) else gate.cancel()
    return runCatching {
      DreamPolicy.markBridge(context)
      context.startActivity(
          Intent(Intent.ACTION_MAIN)
              .addCategory(Intent.CATEGORY_LAUNCHER)
              .setClassName(PACKAGE, "$PACKAGE.MainActivity")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      gate.onLaunched()
      true
    }.getOrElse {
      gate.cancel()
      lifecycle?.removeObserver(observer)
      owner?.let { pendingHosts.remove(it) }
      DreamPolicy.clearBridge(context)
      false
    }
  }
}
