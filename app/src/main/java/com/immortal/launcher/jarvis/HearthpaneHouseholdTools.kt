/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.immortal.launcher.AlarmConfig
import com.immortal.launcher.AlarmRinging
import com.immortal.launcher.AlarmScheduler
import com.immortal.launcher.BriefingActivity
import com.immortal.launcher.BriefingConfig
import com.immortal.launcher.BriefingInputs
import com.immortal.launcher.BriefingLoader
import com.immortal.launcher.BriefingPart
import com.immortal.launcher.BriefingPolicy
import com.immortal.launcher.CallKind
import com.immortal.launcher.Calling
import com.immortal.launcher.CallingStore
import com.immortal.launcher.CcJarvisHome
import com.immortal.launcher.ChimeConfig
import com.immortal.launcher.DinnerCall
import com.immortal.launcher.GuestMode
import com.immortal.launcher.HouseholdListStore
import com.immortal.launcher.HouseholdListSync
import com.immortal.launcher.MoreAlarms
import com.immortal.launcher.SharedTimers
import com.immortal.launcher.GuestContent
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Binds the household tools ([HearthpaneAnnounce], [HearthpaneLists], [HearthpaneAlarms], [HearthpaneTimers],
 * [HearthpaneRoutines], [HearthpaneBriefing], [HearthpaneCall]) to Hearthpane's live features. Runs on the provider's binder thread, inside Jarvis's 5 s tool
 * timeout: the briefing's network sources get [NETWORK_BUDGET_MS] together, and a call is placed on the main thread.
 */
internal object HearthpaneHouseholdTools {
  private const val TAG = "HearthpaneTools"
  private const val NETWORK_BUDGET_MS = 3_000L
  private const val MAIN_WAIT_MS = 1_000L
  private val io = Executors.newCachedThreadPool { r -> Thread(r, "hearthpane-jarvis-io").apply { isDaemon = true } }

  fun build(context: Context): Map<String, (String?) -> JSONObject> {
    val ctx = context.applicationContext
    val use24 = { android.text.format.DateFormat.is24HourFormat(ctx) }
    val announce = HearthpaneAnnounce(
        ready = { DinnerCall.householdReady(ctx) }, selfId = { DinnerCall.selfId(ctx) }, selfName = { DinnerCall.selfName(ctx) },
        peers = { DinnerCall.peers.value }, send = { m, rooms -> DinnerCall.sendFromPortal(ctx, m, rooms) },
        hello = { DinnerCall.hello(ctx) })
    val lists = HearthpaneLists(
        state = { HouseholdListStore.current(ctx) },
        edit = { op -> HouseholdListStore.edit(ctx, op) },
        by = { HouseholdListSync.selfName(ctx) }, now = { HouseholdListStore.clock() },
        hidden = { GuestMode.hides(GuestContent.HOUSEHOLD_LIST) })
    val alarms = HearthpaneAlarms(alarmBook(ctx), use24 = use24)
    val briefing = HearthpaneBriefing(inputs = { briefingInputs(ctx) }, open = { BriefingActivity.open(ctx) },
        hides = { GuestMode.hides(it) }, readsAloud = { readsAloud(ctx) })
    val call = HearthpaneCall(
        ready = { Calling.ready(ctx) }, selfName = { Calling.selfName(ctx) },
        peers = { CallingStore.peers.value }, busy = { Calling.state.value.busy },
        dial = { id, video -> onMain(false) { Calling.dial(ctx, id, CallKind.CALL, video) } })
    val timers = HearthpaneTimers(
        timers = { SharedTimers.snapshot(ctx) },
        start = { name, ms -> SharedTimers.start(ctx, name, ms) },
        act = { action, id -> SharedTimers.act(ctx, action, id) is SharedTimers.ActResult.Changed },
        here = { SharedTimers.selfName(ctx) })
    val routines = HearthpaneRoutines(
        saved = { HomeRoutines.load(ctx) }, save = { HomeRoutines.save(ctx, it) },
        home = { requests -> CcJarvisHome.runBatch(ctx, requests) }, nextAlarm = alarms::nextSpoken)
    return mapOf(
        HearthpaneAnnounce.NAME to announce::invoke,
        HearthpaneRoutines.NAME to routines::invoke,
        HearthpaneTimers.NAME to timers::invoke,
        HearthpaneLists.NAME to lists::invoke,
        HearthpaneAlarms.NAME to alarms::invoke,
        HearthpaneBriefing.NAME to briefing::invoke,
        HearthpaneCall.NAME to call::invoke,
    )
  }

  /** The Portal's alarms: the main one in [AlarmConfig], the others in [MoreAlarms]; ring actions on the main thread. */
  private fun alarmBook(ctx: Context) = object : AlarmBook {
    override fun all() = MoreAlarms.all(ctx)
    override fun save(alarm: MoreAlarms.Alarm) {
      if (alarm.id == MoreAlarms.MAIN_ID) {
        AlarmConfig.save(ctx, alarm.config); AlarmScheduler.reschedule(ctx)
      } else {
        MoreAlarms.edit(ctx) { MoreAlarms.put(it, alarm) }; AlarmScheduler.rescheduleMore(ctx)
      }
    }
    override fun remove(id: Int) {
      if (id == MoreAlarms.MAIN_ID) {
        AlarmConfig.save(ctx, AlarmConfig.load(ctx).copy(enabled = false)); AlarmScheduler.reschedule(ctx)
      } else {
        MoreAlarms.edit(ctx) { all -> all.filterNot { it.id == id } }; AlarmScheduler.rescheduleMore(ctx)
      }
    }
    override fun ringing() = AlarmRinging.current()
    override fun snooze(minutes: Int) = onMain(null) { AlarmRinging.snooze(ctx, minutes) }
    override fun dismiss() = onMain(null) { AlarmRinging.dismiss(ctx) }
  }

  /** The household briefing's inputs: the quick local ones, plus weather and calendar if they answer in time. */
  private fun briefingInputs(ctx: Context): BriefingInputs {
    val now = System.currentTimeMillis()
    val weather = io.submit<Any?> { BriefingLoader.weather(ctx) }
    val events = io.submit<Any?> { BriefingLoader.events(ctx, now) }
    val local = BriefingLoader.local(ctx, now)
    val deadline = System.currentTimeMillis() + NETWORK_BUDGET_MS
    fun <T> within(f: java.util.concurrent.Future<Any?>): BriefingPart<T> = runCatching {
      @Suppress("UNCHECKED_CAST")
      BriefingPart.Ready(f.get(maxOf(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS) as T)
    }.getOrElse { f.cancel(true); Log.i(TAG, "briefing source late: ${it.javaClass.simpleName}"); BriefingPart.Loading }
    return local.copy(weather = within(weather), events = within(events))
  }

  private fun readsAloud(ctx: Context): Boolean = runCatching {
    BriefingConfig.load(ctx).speak &&
        !ChimeConfig.isQuietNow(ctx, BriefingPolicy.minuteOfDay(System.currentTimeMillis(), TimeZone.getDefault()))
  }.getOrDefault(false)

  /** Runs [block] on the main thread and waits briefly for its answer ([late] if it didn't run in time). */
  private fun <T> onMain(late: T, block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val task = FutureTask(block)
    Handler(Looper.getMainLooper()).post(task)
    return runCatching { task.get(MAIN_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrElse {
      task.cancel(false) // never let a late action happen after we've answered that it didn't
      Log.w(TAG, "main-thread action didn't finish in time", it); late
    }
  }
}
