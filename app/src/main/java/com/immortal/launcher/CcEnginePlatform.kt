/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/*
 * What the Control Center engine needs from the Portal, as interfaces, so its state machine
 * (connect, auth, registry, plan, subscriptions, heartbeat, backoff, calls, alerts) runs in JVM
 * tests. [CcAndroidHost], [OkHttpCcSockets], [ExecutorCcScheduler] and [AndroidCcLog] implement
 * them with one call each and no decisions of their own.
 */

/** One Home Assistant websocket. */
internal interface CcSocket {
  fun send(text: String): Boolean
  /** A normal close; its late callbacks are ignored (they belong to an old generation). */
  fun close(code: Int)
  /** Drop the connection at once. */
  fun cancel()
}

/** The socket's callbacks, from any thread. */
internal interface CcSocketEvents {
  fun onMessage(text: String)
  fun onClosed(code: Int)
  fun onFailure(error: Throwable)
}

internal fun interface CcSocketFactory {
  /** Starts connecting to [url]; null when it isn't a usable address. */
  fun open(url: String, events: CcSocketEvents): CcSocket?
}

/** A queued or repeating task; [isDone] once it ran (one-shot) or was cancelled. */
internal interface CcTimer {
  val isDone: Boolean
  fun cancel()
}

/** The engine's one thread: tasks run one at a time, in the order they became due. */
internal interface CcScheduler {
  fun execute(task: () -> Unit)
  fun schedule(delayMs: Long, task: () -> Unit): CcTimer
  /** Every [periodMs] after the previous run ends, first after [periodMs]. */
  fun repeat(periodMs: Long, task: () -> Unit): CcTimer
  /** Runs [task] on the engine thread and waits up to [timeoutMs]; throws on timeout or failure. */
  fun <T> call(timeoutMs: Long, task: () -> T): T
}

internal interface CcLog {
  fun i(message: String)
  fun w(message: String, error: Throwable? = null)
}

/** This Portal's settings, storage and outputs (Context, preferences, files, banners). */
internal interface CcHost {
  fun settings(): ControlCenterConfig.Settings
  fun haConfigured(): Boolean
  fun haBaseUrl(): String
  fun haToken(): String
  /** Whether the Portal has a network at all (unknown counts as yes). */
  fun online(): Boolean

  fun loadPlan(): CcPlan?
  fun savePlan(plan: CcPlan): Boolean

  /** The last-known entity states cached on disk, so even a cold open draws at once ([CcLastKnownStore]). */
  fun loadLastKnown(): Map<String, CcEntity>
  fun saveLastKnown(entities: Map<String, CcEntity>): Boolean

  fun dryRun(): Boolean
  fun setDryRun(on: Boolean)
  fun testPlan(): CcPlan?
  fun saveTestPlan(plan: CcPlan): Boolean
  fun clearTestPlan()

  fun scenesSeen(): Set<String>
  fun markSceneSeen(id: String)

  /** The background alert plan after a live publish ([CcAlertRelay]). */
  fun presentAlerts(plan: CcBackgroundPolicy.Plan, settings: ControlCenterConfig.Settings, appVisible: Boolean, dryRun: Boolean)
  /** One alert straight to the banner policy ([ControlCenterBanners]). */
  fun offerBanner(alert: CcAlert, settings: ControlCenterConfig.Settings, appVisible: Boolean, dryRun: Boolean)
  /** The Home header's Control Center dot. */
  fun homeDot(state: CcUiState, visible: Boolean)
}
