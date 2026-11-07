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
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** What the takeover shows: a live run or a 90 s preview (ticks kept in memory only). */
internal data class RoutineScreen(
    val routine: Routine,
    val run: RoutineRun,
    val preview: Boolean,
    val kidTab: String,
    val kidCheer: String? = null,
    val lastTick: String? = null,
    val chime: String? = null,
    val quiet: Boolean = false,
)

internal data class RoutineCheer(val routine: Routine, val title: String, val sub: String, val quiet: Boolean)

/** A Home toast: [name] in bold, then [rest]; [undoRid] offers Undo for a parent/phone close. */
internal data class RoutineToast(val name: String, val rest: String, val undoRid: String? = null)

/**
 * Picture routines on this Portal, main thread only: starts and closes runs, owns the takeover's
 * state (Compose-observable) and re-arms the single [RoutineScheduler] alarm after every change.
 * Transient UI (chime bubble, cheer, toasts, preview end) uses one-shot Handler posts.
 */
internal object RoutineEngine : RoutineActions {
  private const val TAG = "ImmortalRoutines"
  private const val CHEER_MS = 5_200L
  private const val TOAST_MS = 10_000L
  private const val SHORT_TOAST_MS = 4_000L
  private const val KID_CHEER_MS = 2_600L
  private const val CHIME_MS = 4_500L
  const val PREVIEW_MS = 90_000L
  private const val BUSY_RETRY_MS = 60_000L

  private lateinit var app: Context
  lateinit var repo: RoutineRepository
    private set
  private val main = Handler(Looper.getMainLooper())

  var screen by mutableStateOf<RoutineScreen?>(null)
    private set
  var cheer by mutableStateOf<RoutineCheer?>(null)
    private set
  var toast by mutableStateOf<RoutineToast?>(null)
    private set
  /** Bumped on every change so Home's widget re-reads the store. */
  var revision by mutableIntStateOf(0)
    private set
  /** True while the takeover Activity is started. */
  var visible = false

  @Synchronized fun init(context: Context, clock: RoutineClock = SystemRoutineClock) {
    if (::app.isInitialized) return
    app = context.applicationContext
    repo = RoutineRepository(PrefsRoutineStorage(app), clock)
  }

  private fun now() = repo.clock.nowLocalMs()

  /** Brings runs in line with the clock: closes expired ones, starts a due one, re-arms. */
  fun step() {
    val now = now()
    val doc = repo.doc()
    var runs = repo.runs()
    RoutineSchedule.expired(doc, runs, now).forEach { (run, why) ->
      val closed = RoutineRunLogic.close(run, why, now)
      repo.putRun(closed)
      runs = runs + (closed.rid to closed)
      if (screen?.run?.rid == run.rid && screen?.preview == false) screen = null
      doc.routines.firstOrNull { it.id == run.rid }?.takeIf { why == "auto" }?.let { r ->
        val (done, total) = RoutineRunLogic.totals(r, run)
        show(RoutineToast(r.name, " closed at ${clock(now)}. $done of $total done."), TOAST_MS)
      }
    }
    var retry = false
    val active = RoutineSchedule.active(runs)
    val current = screen
    if (active != null) {
      val r = doc.routines.first { it.id == active.rid }
      when {
        current != null && !current.preview && current.run.rid == r.id -> {
          screen = current.copy(routine = r)
          if (!visible && !RoutineScheduler.busy(app)) RoutineScheduler.launch(app)
        }
        RoutineScheduler.busy(app) -> retry = true // README: wait for the call to end, no chime
        else -> open(r, active, chime = false)
      }
    } else RoutineSchedule.dueToStart(doc, runs, now)?.let { r ->
      if (RoutineScheduler.busy(app)) retry = true
      else {
        val run = RoutineSchedule.startRun(r, now, manual = false)
        repo.putRun(run)
        runs = runs + (run.rid to run)
        open(r, run, chime = true)
      }
    }
    rearm(doc, runs, now, retry)
    revision++
  }

  private fun rearm(doc: RoutineDoc, runs: Map<String, RoutineRun>, now: Long, retry: Boolean) {
    val next = listOfNotNull(RoutineSchedule.nextWake(doc, runs, now), if (retry) now + BUSY_RETRY_MS else null).minOrNull()
    RoutineScheduler.arm(app, next?.let(repo.clock::toUtcMs))
  }

  private fun clock(ms: Long) = RoutineDates.clock12(RoutineDates.minuteOfDay(ms))
  private fun quiet() = runCatching { ChimeConfig.isQuietNow(app, RoutineDates.minuteOfDay(now())) }.getOrDefault(false)

  private fun open(r: Routine, run: RoutineRun, chime: Boolean, preview: Boolean = false) {
    val quiet = quiet()
    val text = if (chime) "Time for ${r.name}" else null
    screen = RoutineScreen(r, run, preview, RoutineRunLogic.kidKeys(r).first(), chime = text, quiet = quiet)
    toast = null
    cheer = null
    if (text != null) {
      if (!quiet) runCatching { ChimePlayer.announce(app, text) }.onFailure { Log.w(TAG, "chime failed", it) }
      main.postDelayed({ screen?.let { if (it.chime == text) screen = it.copy(chime = null) } }, CHIME_MS)
    }
    RoutineScheduler.launch(app)
  }

  private var toastSeq = 0
  private fun show(t: RoutineToast, ms: Long) {
    toast = t
    val seq = ++toastSeq
    main.postDelayed({ if (seq == toastSeq) toast = null }, ms)
  }

  fun dismissToast() { toastSeq++; toast = null }

  /** One tap on a card: tick or untick; may cheer a kid or finish the routine. */
  fun tick(cardId: String, kid: String) {
    val s = screen ?: return
    val now = now()
    val res = RoutineRunLogic.toggle(s.routine, s.run, cardId, kid, now)
    if (res.event == RoutineRunLogic.TickEvent.IGNORED) return
    if (!s.preview && !repo.putRun(res.run)) Log.w(TAG, "tick not persisted (storage)")
    when (res.event) {
      RoutineRunLogic.TickEvent.ALL_DONE -> finishWithCheer(s, res.run, now)
      RoutineRunLogic.TickEvent.KID_DONE -> {
        screen = s.copy(run = res.run, kidCheer = kid, lastTick = cardId)
        main.postDelayed({
          screen?.let { if (it.kidCheer == kid) screen = it.copy(kidCheer = null, kidTab = RoutineRunLogic.nextKid(it.routine, it.run, kid)) }
        }, KID_CHEER_MS)
      }
      else -> screen = s.copy(run = res.run, lastTick = if (res.event == RoutineRunLogic.TickEvent.TICKED) cardId else null)
    }
    revision++
  }

  fun selectKid(kid: String) { screen?.let { if (kid in RoutineRunLogic.kidKeys(it.routine)) screen = it.copy(kidTab = kid) } }

  private fun finishWithCheer(s: RoutineScreen, run: RoutineRun, now: Long) {
    screen = null
    val quiet = quiet()
    cheer = RoutineCheer(s.routine, "All done!",
        if (s.preview) "That was a preview. Nothing was saved." else RoutineRunLogic.cheerLine(run, now), quiet)
    if (!quiet) runCatching { ChimePlayer.announce(app, "You did it!") }
    main.postDelayed({
      cheer = null
      show(if (s.preview) RoutineToast("", "Preview finished") else RoutineToast(s.routine.name, " done at ${clock(now)}"), TOAST_MS)
    }, CHEER_MS)
    if (!s.preview) step()
  }

  /** Press-and-hold close on the Portal (README: 1.5 s hold, then a toast with Undo). */
  fun holdClose() {
    val s = screen ?: return
    if (s.preview) { endPreview(); return }
    closeRun(s.run, "parent")
  }

  private fun closeRun(run: RoutineRun, by: String) {
    val closed = RoutineRunLogic.close(run, by, now())
    repo.putRun(closed)
    val name = repo.doc().routines.firstOrNull { it.id == run.rid }?.name ?: "The routine"
    if (screen?.run?.rid == run.rid) screen = null
    show(RoutineToast(name, " closed for today", undoRid = run.rid), TOAST_MS)
    step()
  }

  fun undoClose(rid: String) {
    val run = repo.runs()[rid] ?: return
    val back = RoutineRunLogic.undoClose(run) ?: return
    val r = repo.doc().routines.firstOrNull { it.id == rid } ?: return
    if (RoutineSchedule.active(repo.runs()) != null) return
    repo.putRun(back)
    dismissToast()
    open(r, back, chime = false)
    step()
  }

  /** "Start now" from the Routines sheet. */
  fun startNow(rid: String) {
    if (RoutineSchedule.active(repo.runs()) != null) { reopen(); return }
    val r = repo.doc().routines.firstOrNull { it.id == rid && it.enabled } ?: return
    val run = RoutineSchedule.startRun(r, now(), manual = true)
    repo.putRun(run)
    open(r, run, chime = true)
    step()
  }

  /** Brings back a running takeover (e.g. after a kid pressed Home). */
  fun reopen() {
    if (screen != null) RoutineScheduler.launch(app) else step()
  }

  fun endPreview() {
    if (screen?.preview != true) return
    screen = null
    show(RoutineToast("", "Preview ended"), SHORT_TOAST_MS)
  }

  // ---- RoutineActions, called from the route thread ----

  override fun changed() { onMain { step() } }

  override fun closeFromPhone(rid: String): Boolean = onMain {
    val run = repo.runs()[rid]?.takeIf { it.status == RunStatus.RUNNING } ?: return@onMain false
    closeRun(run, "phone")
    true
  } ?: false

  override fun preview(routine: Routine): Boolean = onMain {
    if (RoutineSchedule.active(repo.runs()) != null) return@onMain false
    val now = now()
    val run = RoutineRun(routine.id, RoutineDates.day(now), now, now + (routine.end - routine.start) * RoutineDates.MINUTE_MS)
    open(routine, run, chime = false, preview = true)
    val shown = screen
    main.postDelayed({ if (screen === shown) endPreview() }, PREVIEW_MS)
    true
  } ?: false

  /** Runs [block] on the main thread and waits briefly for its answer (the phone needs an ack). */
  private fun <T> onMain(block: () -> T): T? {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val out = AtomicReference<T?>()
    val latch = CountDownLatch(1)
    main.post { try { out.set(block()) } catch (e: Exception) { Log.w(TAG, "routine action failed", e) } finally { latch.countDown() } }
    return if (latch.await(2, TimeUnit.SECONDS)) out.get() else null
  }
}
