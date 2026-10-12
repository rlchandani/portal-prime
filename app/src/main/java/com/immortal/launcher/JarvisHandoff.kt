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
  private val pendingHosts = mutableMapOf<LifecycleOwner, () -> Unit>()

  private fun host(context: Context): LifecycleOwner? = generateSequence(context) { current ->
    (current as? ContextWrapper)?.baseContext?.takeUnless { it === current }
  }.filterIsInstance<LifecycleOwner>().firstOrNull()

  /** Tab changes retain their Activity, so they explicitly cancel pending preparation. */
  fun cancelPending(context: Context) { host(context)?.let { pendingHosts[it]?.invoke() } }

  /** True acknowledges preparation; onLaunched closes standalone callers after actual dispatch. */
  fun launch(context: Context, onLaunched: () -> Unit = {}): Boolean {
    val owner = host(context)
    val lifecycle = owner?.lifecycle
    // UI callers have a started Activity. A non-UI caller can open Jarvis, but must not start
    // a potentially background microphone without a foreground handoff to observe.
    val canObserve = lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
    if (canObserve && owner != null && owner in pendingHosts) return true
    lateinit var observer: LifecycleEventObserver
    var ticket = 0L
    fun retire(clear: Boolean) {
      lifecycle?.removeObserver(observer)
      owner?.let { pendingHosts.remove(it) }
      if (clear && ticket != 0L) DreamPolicy.clearBridgeIfCurrent(context, ticket)
    }
    val gate = JarvisForegroundWake {
      retire(clear = false)
      runCatching {
        context.sendBroadcast(Intent(WAKE_ACTION)
            .setClassName(PACKAGE, "$PACKAGE.wake.WakeHandoffReceiver")
            .putExtra(WAKE_ID, "immortal-tap"))
      }
    }
    val request = JarvisPreparedLaunch(
        current = { DreamPolicy.isCurrentBridge(ticket) &&
            (!canObserve || lifecycle?.currentState == Lifecycle.State.RESUMED) },
        dispatch = {
          context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
              .setClassName(PACKAGE, "$PACKAGE.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          true
        },
        succeeded = { gate.onLaunched(); runCatching(onLaunched) },
        failed = {
          gate.cancel(); retire(clear = true)
          android.widget.Toast.makeText(context, "Jarvis couldn’t be opened.",
              android.widget.Toast.LENGTH_LONG).show()
        },
        superseded = { gate.cancel(); retire(clear = true) },
    )
    observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE -> if (!request.launched) {
          request.cancel(); gate.cancel(); retire(clear = true)
        }
        Lifecycle.Event.ON_STOP -> if (request.launched) gate.onHostStopped()
        Lifecycle.Event.ON_DESTROY -> {
          request.cancel(); gate.cancel(); retire(clear = !request.launched)
        }
        else -> Unit
      }
    }
    if (canObserve && owner != null) {
      pendingHosts[owner] = {
        if (!request.launched) {
          request.cancel(); gate.cancel(); retire(clear = true)
        }
      }
      lifecycle?.addObserver(observer)
    } else gate.cancel()
    return runCatching {
      ticket = DreamPolicy.markBridgeAsync(context) { _, committed -> request.prepared(committed) }
      true
    }.getOrElse {
      request.cancel(); gate.cancel(); retire(clear = true)
      false
    }
  }
}
