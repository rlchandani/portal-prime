/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Home Control Center runtime facade. Screens call [setVisible] from onStart/onStop (live state
 * only while someone looks; banners keep a small alert subscription when enabled), read [state]
 * and act through [send] and friends. Nothing is ever queued: while Home Assistant isn't live an
 * action is refused with the reason as a toast and the controls render disabled.
 */
internal object ControlCenter {
  @Volatile private var engine: CcEngine? = null
  private val main by lazy { Handler(Looper.getMainLooper()) }

  private fun engine(context: Context): CcEngine =
      engine ?: synchronized(this) {
        engine ?: CcEngine(CcAndroidHost(context.applicationContext), OkHttpCcSockets(), ExecutorCcScheduler(), AndroidCcLog)
            .also { engine = it }
      }

  fun state(context: Context): StateFlow<CcUiState> = engine(context).flow

  /** App start: keep the background alert subscription if this Portal shows banners. */
  fun install(context: Context) = engine(context).install()

  fun setVisible(context: Context, visible: Boolean) = engine(context).setVisible(visible)

  /** HA address/token or Control Center settings changed. */
  fun restart(context: Context) = engine(context).restartWithSettings()

  /** Presence and recognitions changed ([RoomSignals]); republished only while someone looks. */
  fun setSignals(context: Context, signals: CcRoomSignals) = engine(context).setSignals(signals)

  fun reloadSettings(context: Context) {
    engine(context).reloadSettings()
    RoomSignals.refresh()
  }

  fun retryNow(context: Context) = engine(context).retryNow()

  /**
   * Sends [call] now, or refuses (returns false) when HA isn't live or [CcPolicy.refusal] says no.
   * Every call passes this one gate: [confirmed] is true only from the household's answer to a
   * modal or the PIN pad ([CcUi.confirmed], the alarm paths); sliders and dials send unconfirmed.
   */
  fun send(context: Context, call: CcCall, undoable: Boolean = true, onResult: ((Boolean, String?) -> Unit)? = null,
           confirmed: Boolean = false): Boolean = engine(context).send(call, undoable, onResult, confirmed)

  /**
   * A paired phone is looking ([CcRemoteRoute]): hold the live, on-screen subscription for
   * [CcRemoteWatch.LEASE_MS] more. Banners, plan models and the Home dot still follow this
   * Portal's own screen only. When the last lease lapses the socket drops back to background.
   */
  fun remoteWatch(context: Context) = engine(context).remoteWatch()

  /**
   * Sends a paired phone's call. [CcPhonePolicy] has already passed it (with the household's asks
   * and the phone's confirmation); kids mode is per Portal and doesn't apply. Null = sent and
   * [onResult] will run on the engine thread; else why not (HA isn't live).
   */
  fun sendFromPhone(context: Context, call: CcCall, onResult: (Boolean, String?) -> Unit): String? =
      engine(context).sendFromPhone(call, onResult)

  fun undo(context: Context) = engine(context).undo()

  fun dismissToast(context: Context, id: Long) = engine(context).dismissToast(id)

  /** Runs a scene/script in HA and walks the house on the plan while it happens. */
  fun runScene(context: Context, scene: CcEntity, confirmed: Boolean = false): Boolean = engine(context).runScene(scene, confirmed)

  fun clearSceneRun(context: Context) = engine(context).clearSceneRun()

  /**
   * Arm/disarm; [onResult] on the main thread (false + HA's message for a wrong code). Called only
   * from the PIN pad and the arm check, which are the household's confirmation.
   */
  fun alarm(context: Context, panel: CcEntity, mode: CcAlarmMode, code: String?, onResult: (Boolean, String?) -> Unit) {
    val delivered: (Boolean, String?) -> Unit = { ok, msg -> main.post { onResult(ok, msg) } }
    if (!send(context, CcCalls.alarm(panel, mode, code), undoable = false, onResult = delivered, confirmed = true)) delivered(false, null)
  }

  /** Fleet `cc_debug` (operator only): dry run, local test plan, HA-unreachable switch. Blocks briefly. */
  fun debug(context: Context, dryRun: Boolean?, testPlan: String?, testAlert: String? = null, haUnreachable: Boolean? = null,
            fakeState: CcFakeState? = null): String =
      engine(context).debugCommand(dryRun, testPlan, testAlert, haUnreachable, fakeState, DEBUG_WAIT_MS)

  fun savePlan(context: Context, plan: CcPlan) = engine(context).queueSavePlan(plan)

  fun dismissAlert(context: Context, alert: CcAlert, untilMs: Long) = engine(context).dismissAlert(alert, untilMs)

  /** A camera's HLS URL from HA (`camera/stream`), absolute, or null; [onUrl] runs on the engine thread. */
  fun cameraStream(context: Context, entityId: String, onUrl: (String?) -> Unit) = engine(context).cameraStream(entityId, onUrl)

  /** Loads this week's home charging (daily recorder statistics) for the Tesla screen. Reads HA only, never the car. */
  fun loadChargeWeek(context: Context) = engine(context).loadChargeWeek()

  internal fun isoUtc(ms: Long): String =
      SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

  /** Local-midnight starts of the last 7 days (today last). */
  internal fun weekBuckets(now: Long, zone: TimeZone): List<Long> {
    val c = Calendar.getInstance(zone).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    c.add(Calendar.DAY_OF_YEAR, -(DAYS - 1))
    return (0 until DAYS).map { d -> (c.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, d) }.timeInMillis }
  }

  private const val DEBUG_WAIT_MS = 5_000L
  private const val DAYS = 7
  const val TAG = CcEngine.TAG
}
