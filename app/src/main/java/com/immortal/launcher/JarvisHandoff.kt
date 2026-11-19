/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.immortal.launcher.jarvis.HearthpaneScreens
import com.immortal.launcher.jarvis.JarvisFloatLaunch
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

/** Jarvis's exported float and wake contracts; no dependency on, or configuration changes to, the assistant. */
internal object JarvisHandoff {
  const val PACKAGE = "com.portal.assistant"
  private const val WAKE_ACTION = "com.portal.wake.action.WAKE"
  private const val WAKE_ID = "com.portal.wake.extra.ID"
  private const val TAG = "JarvisHandoff"
  // UI-thread only. Rapid taps on the same launching Activity share its pending handoff; a stuck one expires.
  private val pendingHosts = JarvisPendingTaps<LifecycleOwner>()

  private fun host(context: Context): LifecycleOwner? = generateSequence(context) { current ->
    (current as? ContextWrapper)?.baseContext?.takeUnless { it === current }
  }.filterIsInstance<LifecycleOwner>().firstOrNull()

  /** Tab changes retain their Activity, so they explicitly cancel pending preparation. */
  fun cancelPending(context: Context) { host(context)?.let { pendingHosts.cancel(it) } }

  /**
   * True while a Jarvis tap on this screen is still being prepared. A screen that resumes in that window (Home
   * reappearing as a closing card finishes) must not clear the photo-frame bridge: that supersedes the tap.
   */
  fun isPreparing(context: Context): Boolean =
      host(context)?.let { pendingHosts.isPreparing(it, SystemClock.uptimeMillis()) } == true

  /**
   * Every Hearthpane Jarvis entry point. With Floating Jarvis installed (Jarvis 2.6+) one intent opens the ember card
   * over the screen in front — no WAKE broadcast and no wait for this screen to stop, since the card only pauses it.
   * An older Jarvis gets the full-app handoff. True acknowledges preparation; onLaunched closes standalone callers
   * after actual dispatch.
   */
  fun launch(context: Context, onLaunched: () -> Unit = {}): Boolean {
    val tapUptimeMs = SystemClock.uptimeMillis()
    return when (JarvisFloatLaunch.route(floatInstalled(context))) {
      JarvisFloatLaunch.Route.FLOAT -> launchFloat(context, tapUptimeMs, onLaunched)
      JarvisFloatLaunch.Route.FULL_APP -> launchFullApp(context, onLaunched)
    }
  }

  /**
   * "Hey Jarvis" heard on a Hearthpane screen ([HearthpaneWake]): the same handoff as a tap on [context]'s screen,
   * timed from [heardAtUptimeMs] and opening the card in its wake state. The mic is already released.
   */
  fun launchFromWake(context: Context, heardAtUptimeMs: Long): Boolean =
      when (JarvisFloatLaunch.route(floatInstalled(context))) {
        JarvisFloatLaunch.Route.FLOAT -> launchFloat(context, heardAtUptimeMs, onLaunched = {}, wake = true)
        JarvisFloatLaunch.Route.FULL_APP -> launchFullApp(context, onLaunched = {})
      }

  /** False when this tap joins a preparation already pending for [owner]; every decision is logged. */
  private fun admit(owner: LifecycleOwner, route: String): Boolean =
      when (pendingHosts.admit(owner, SystemClock.uptimeMillis())) {
        JarvisPendingTaps.Admission.NEW -> true
        JarvisPendingTaps.Admission.JOINED -> { Log.i(TAG, "$route tap joined a pending preparation"); false }
        JarvisPendingTaps.Admission.REPLACED_STALE -> {
          Log.w(TAG, "$route tap replaced a preparation that never completed"); true
        }
      }

  /** Why a prepared tap did not open Jarvis, for the log: a newer bridge, or this screen is no longer resumed. */
  private fun supersededReason(ticket: Long, lifecycle: Lifecycle?, canObserve: Boolean): String = when {
    !DreamPolicy.isCurrentBridge(ticket) -> "a newer photo-frame bridge"
    canObserve -> "host ${lifecycle?.currentState}"
    else -> "unknown"
  }

  private fun floatInstalled(context: Context): Boolean = runCatching {
    context.packageManager.getActivityInfo(ComponentName(PACKAGE, JarvisFloatLaunch.FLOAT_ACTIVITY), 0)
    true
  }.getOrDefault(false)

  /** What the card is told: the screen behind it, quiet hours, a Hearthpane call, the theme, the tap time. */
  private fun floatIntent(context: Context, tapUptimeMs: Long, wake: Boolean): Intent {
    val request = JarvisFloatLaunch.Request(
        screen = runCatching { HearthpaneScreens.current()?.id?.invoke() }.getOrNull(),
        quiet = ChimeConfig.isQuietNow(context,
            BriefingPolicy.minuteOfDay(System.currentTimeMillis(), java.util.TimeZone.getDefault())),
        callActive = Calling.state.value.busy,
        light = ImmortalSettings.settingsTheme(context) == "light",
        tapUptimeMs = tapUptimeMs,
        wake = wake,
    )
    val intent = Intent().setClassName(PACKAGE, JarvisFloatLaunch.FLOAT_ACTIVITY)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    JarvisFloatLaunch.extras(request).forEach { (key, value) ->
      when (value) {
        is Boolean -> intent.putExtra(key, value)
        is Long -> intent.putExtra(key, value)
        else -> intent.putExtra(key, value.toString())
      }
    }
    return intent
  }

  /**
   * The floating card: the same durable photo-frame bridge and one-shot preparation as the full app, then one
   * intent. The caller is only paused behind the card, so nothing waits for ON_STOP and there is no WAKE broadcast:
   * Jarvis opens its own mic once its card is the top resumed Activity.
   */
  private fun launchFloat(context: Context, tapUptimeMs: Long, onLaunched: () -> Unit, wake: Boolean = false): Boolean {
    val owner = host(context)
    val lifecycle = owner?.lifecycle
    val canObserve = lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
    if (canObserve && owner != null && !admit(owner, "float")) return true
    val resumedAtTap = lifecycle?.currentState == Lifecycle.State.RESUMED
    lateinit var observer: LifecycleEventObserver
    var ticket = 0L
    var held: JarvisPendingTaps.Ticket? = null
    fun retire(clear: Boolean) {
      lifecycle?.removeObserver(observer)
      owner?.let { pendingHosts.release(it, held) }
      if (clear && ticket != 0L) DreamPolicy.clearBridgeIfCurrent(context, ticket)
    }
    val request = JarvisPreparedLaunch(
        current = { DreamPolicy.isCurrentBridge(ticket) &&
            (!canObserve || JarvisTapHost.stillShowing(resumedAtTap, lifecycle?.currentState)) },
        dispatch = { context.startActivity(floatIntent(context, tapUptimeMs, wake)); true },
        succeeded = {
          Log.i(TAG, "float card started ${SystemClock.uptimeMillis() - tapUptimeMs} ms after the " +
              if (wake) "wake word" else "tap")
          retire(clear = false); runCatching(onLaunched)
        },
        failed = {
          Log.w(TAG, "float card could not be started")
          retire(clear = true)
          android.widget.Toast.makeText(context, "Jarvis couldn’t be opened.",
              android.widget.Toast.LENGTH_LONG).show()
        },
        superseded = {
          Log.i(TAG, "float tap dropped: ${supersededReason(ticket, lifecycle, canObserve)}")
          retire(clear = true)
        },
    )
    observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP ->
          if (!request.launched) { request.cancel(); retire(clear = true) }
        Lifecycle.Event.ON_DESTROY -> { request.cancel(); retire(clear = !request.launched) }
        else -> Unit
      }
    }
    if (canObserve && owner != null) {
      held = pendingHosts.hold(owner, SystemClock.uptimeMillis()) {
        if (!request.launched) { request.cancel(); retire(clear = true) }
      }
      lifecycle?.addObserver(observer)
    }
    return runCatching {
      ticket = DreamPolicy.markBridgeAsync(context) { _, committed -> request.prepared(committed) }
      true
    }.getOrElse {
      request.cancel(); retire(clear = true)
      false
    }
  }

  /** An older Jarvis without the floating card: open the full app, then wake its mic once this screen has stopped. */
  private fun launchFullApp(context: Context, onLaunched: () -> Unit): Boolean {
    val owner = host(context)
    val lifecycle = owner?.lifecycle
    // UI callers have a started Activity. A non-UI caller can open Jarvis, but must not start
    // a potentially background microphone without a foreground handoff to observe.
    val canObserve = lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
    if (canObserve && owner != null && !admit(owner, "full-app")) return true
    lateinit var observer: LifecycleEventObserver
    var ticket = 0L
    var held: JarvisPendingTaps.Ticket? = null
    fun retire(clear: Boolean) {
      lifecycle?.removeObserver(observer)
      owner?.let { pendingHosts.release(it, held) }
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
        superseded = {
          Log.i(TAG, "full-app tap dropped: ${supersededReason(ticket, lifecycle, canObserve)}")
          gate.cancel(); retire(clear = true)
        },
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
      held = pendingHosts.hold(owner, SystemClock.uptimeMillis()) {
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
