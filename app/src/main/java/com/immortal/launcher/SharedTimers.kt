/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import java.security.SecureRandom
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Entry point for the integrator: `SharedTimersFeature.install(this)` from [ImmortalApp.onCreate]. */
internal object SharedTimersFeature {
  fun install(context: Context) = SharedTimers.install(context)
}

/**
 * Household timers runtime: one list of [SharedTimer]s per Portal, kept in step over the signed
 * [HouseholdBus] ([SharedTimerWire]) and served to the phone at `/remote/timers`
 * ([SharedTimerRoute]). Every surface — Tools › Timers, the Home "Timers" widget, the phone —
 * changes timers only through [start] and [act]. A running timer arms this Portal's own alarm in
 * the [TimerConfig] ledger, so it rings here even if no message arrives; [TimerReceiver] hands the
 * alarm back through [onAlarm]. Saved to SharedPreferences, so timers survive an app restart.
 *
 * Bus handlers run on the listener thread; state changes are serialized by [lock] and
 * presentation is posted to the main thread by [SharedTimerPresenter].
 */
internal object SharedTimers {
  private const val TAG = "HearthpaneTimers"
  private const val PREFS = "immortal_shared_timers"
  private const val KEY = "timers"
  private const val ID_BYTES = 6
  /** Answer another Portal's "what's running?" at most this often. */
  private const val ASK_REPLY_MIN_MS = 10_000L
  /** Re-send our newer copy to a stale sender at most this often per timer. */
  private const val CORRECTION_MIN_MS = 5_000L
  const val ROUTE = "/remote/timers"

  /** Injectable for tests and the quiet-hours decision. */
  @Volatile internal var clock: () -> Long = System::currentTimeMillis
  @Volatile internal var zone: () -> TimeZone = TimeZone::getDefault

  sealed interface ActResult {
    data class Changed(val timer: SharedTimer) : ActResult
    object Unknown : ActResult
    object NotApplicable : ActResult
  }

  private val lock = Any()
  private val random = SecureRandom()
  private val mutable = MutableStateFlow<List<SharedTimer>>(emptyList())
  /** Every retained timer (live and recent tombstones); use [SharedTimerMerge.display] to show them. */
  val timers: StateFlow<List<SharedTimer>> = mutable.asStateFlow()
  @Volatile private var loaded = false
  @Volatile private var installed = false
  @Volatile private var lastAskReplyMs = 0L
  private val lastCorrection = ConcurrentHashMap<String, Long>()
  private const val MAX_CORRECTION_ENTRIES = 64

  fun install(context: Context) {
    if (installed) return
    installed = true
    val app = context.applicationContext
    SharedTimerWire.TIMER_TYPES.forEach { type ->
      HouseholdBus.register(type) { c, m -> SharedTimerWire.decode(m.body, clock())?.let { onRemote(c, it) } }
    }
    HouseholdBus.register(SharedTimerWire.ASK) { c, _ -> onAsk(c) }
    RemoteFeatureRoutes.register(ROUTE, SharedTimerRoute)
    runCatching { SharedTimerStartup.reconcile(app) }.onFailure { Log.w(TAG, "could not restore timers", it) }
    HouseholdBus.send(app, SharedTimerWire.ASK, JSONObject())
  }

  fun selfId(context: Context): String? = runCatching { RemoteCompanionStore.id(context) }.getOrNull()
  fun selfName(context: Context): String = DinnerCall.selfName(context)

  fun snapshot(context: Context): List<SharedTimer> {
    ensureLoaded(context)
    return mutable.value
  }

  /** Starts a timer here (or for the phone, with its [room] and [SharedTimer.PHONE_ORIGIN]). */
  fun start(
      context: Context, name: String, durationMs: Long,
      room: String = selfName(context), origin: String? = null,
  ): SharedTimer? {
    val self = selfId(context) ?: return null
    val clean = DinnerWire.cleanName(name) ?: SharedTimer.DEFAULT_NAME
    val timer = SharedTimerOps.create(newId(), clean, room, origin ?: self, durationMs, clock(), self) ?: return null
    return commit(context, timer, SharedTimerWire.SET, broadcast = true)
  }

  fun act(context: Context, action: SharedTimerAction, id: String): ActResult {
    val self = selfId(context) ?: return ActResult.NotApplicable
    val local = snapshot(context).firstOrNull { it.id == id } ?: return ActResult.Unknown
    val now = clock()
    val next = when (action) {
      SharedTimerAction.PAUSE -> SharedTimerOps.pause(local, now, self)
      SharedTimerAction.RESUME -> SharedTimerOps.resume(local, now, self)
      SharedTimerAction.ADD -> SharedTimerOps.addMinute(local, now, self)
      SharedTimerAction.CANCEL -> SharedTimerOps.cancel(local, now, self)
      SharedTimerAction.STOP -> SharedTimerOps.stop(local, now, self)
    } ?: return ActResult.NotApplicable
    return commit(context, next, SharedTimerWire.UPDATE, broadcast = true)?.let(ActResult::Changed) ?: ActResult.NotApplicable
  }

  /**
   * This Portal's alarm for [id] fired. True when it is a household timer (it now rings everywhere,
   * or was already stopped/paused elsewhere); false for a local-only legacy timer, which the
   * receiver rings itself. Never throws: a storage failure falls back to the legacy ring.
   */
  fun onAlarm(context: Context, id: String): Boolean = runCatching {
    val local = snapshot(context).firstOrNull { it.id == id } ?: return@runCatching false
    val self = selfId(context) ?: return@runCatching true
    SharedTimerOps.ring(local, clock(), self)?.let { commit(context, it, SharedTimerWire.DONE, broadcast = true) }
    true
  }.getOrElse { Log.w(TAG, "timer alarm could not be shared", it); false }

  /** A ring nobody answered ends here quietly; the other rooms do the same on their own. */
  fun expireRing(context: Context, id: String) {
    val local = snapshot(context).firstOrNull { it.id == id } ?: return
    if (SharedTimerMerge.unanswered(listOf(local), clock()).isEmpty()) return
    val self = selfId(context) ?: return
    SharedTimerOps.stop(local, clock(), self)?.let { commit(context, it, SharedTimerWire.UPDATE, broadcast = false) }
  }

  /** Applies a change made on this device (or restored at startup); null when it was refused. */
  internal fun commit(context: Context, next: SharedTimer, type: String, broadcast: Boolean): SharedTimer? {
    val result = synchronized(lock) {
      ensureLoaded(context)
      val merged = SharedTimerMerge.merge(mutable.value, next)
      if (merged.outcome == SharedTimerMerge.Outcome.APPLIED) save(context, merged.timers)
      merged
    }
    if (result.outcome != SharedTimerMerge.Outcome.APPLIED) return null
    effects(context, result.before, next)
    if (broadcast) HouseholdBus.send(context, type, SharedTimerWire.encode(next, clock()))
    return next
  }

  private fun onRemote(context: Context, incoming: SharedTimer) {
    val result = synchronized(lock) {
      ensureLoaded(context)
      val merged = SharedTimerMerge.merge(mutable.value, incoming)
      if (merged.outcome == SharedTimerMerge.Outcome.APPLIED) save(context, merged.timers)
      merged
    }
    when (result.outcome) {
      SharedTimerMerge.Outcome.APPLIED -> effects(context, result.before, incoming)
      SharedTimerMerge.Outcome.STALE -> result.before?.let { correct(context, it) }
      SharedTimerMerge.Outcome.DUPLICATE, SharedTimerMerge.Outcome.REJECTED -> Unit
    }
  }

  /** The sender has an older version (it missed a change while away); send it ours. */
  private fun correct(context: Context, newer: SharedTimer) {
    val now = clock()
    val last = lastCorrection[newer.id] ?: 0L
    if (now - last < CORRECTION_MIN_MS) return
    if (lastCorrection.size >= MAX_CORRECTION_ENTRIES) lastCorrection.clear() // ids of long-gone timers
    lastCorrection[newer.id] = now
    HouseholdBus.send(context, SharedTimerWire.UPDATE, SharedTimerWire.encode(newer, now))
  }

  private fun onAsk(context: Context) {
    val now = clock()
    if (now - lastAskReplyMs < ASK_REPLY_MIN_MS) return
    lastAskReplyMs = now
    snapshot(context).filter { it.state.live }.forEach {
      HouseholdBus.send(context, SharedTimerWire.UPDATE, SharedTimerWire.encode(it, now))
    }
  }

  /** Keeps this Portal's alarm in step and shows/hides the "Timer done" card. */
  private fun effects(context: Context, before: SharedTimer?, after: SharedTimer) {
    runCatching {
      if (after.state == SharedTimerState.RUNNING) {
        TimerConfig.arm(context, TimerConfig.Timer(after.id, after.name, after.durationMs, after.endAtMs))
      } else {
        TimerConfig.disarm(context, after.id)
      }
    }.onFailure { Log.w(TAG, "could not update the timer alarm", it) }
    SharedTimerPresenter.changed(context, before, after)
  }

  private fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(lock) {
      if (loaded) return
      val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
      mutable.value = SharedTimerWire.decodeStore(raw)
      loaded = true
    }
  }

  /** Caller holds [lock]. Prunes old tombstones on every write. */
  private fun save(context: Context, timers: List<SharedTimer>) {
    val kept = SharedTimerMerge.prune(timers, clock())
    mutable.value = kept
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, SharedTimerWire.encodeStore(kept)).apply()
  }

  /** Startup only: replaces the list after restoring (see [SharedTimerStartup]). */
  internal fun replaceAll(context: Context, timers: List<SharedTimer>) = synchronized(lock) {
    ensureLoaded(context)
    save(context, timers)
  }

  fun newId(): String = ByteArray(ID_BYTES).also(random::nextBytes).let(HouseholdKey::toHex)
}
