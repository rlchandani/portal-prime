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
import com.immortal.launcher.ChimeConfig
import com.immortal.launcher.DinnerCall
import com.immortal.launcher.GuestMode
import com.immortal.launcher.HouseholdListStore
import com.immortal.launcher.HouseholdListSync
import com.immortal.launcher.GuestContent
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Binds the household tools ([HearthpaneAnnounce], [HearthpaneLists], [HearthpaneAlarms], [HearthpaneBriefing],
 * [HearthpaneCall]) to Hearthpane's live features. Runs on the provider's binder thread, inside Jarvis's 5 s tool
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
    val alarms = HearthpaneAlarms(
        load = { AlarmConfig.load(ctx) },
        save = { c -> AlarmConfig.save(ctx, c); AlarmScheduler.reschedule(ctx) }, use24 = use24)
    val briefing = HearthpaneBriefing(inputs = { briefingInputs(ctx) }, open = { BriefingActivity.open(ctx) },
        hides = { GuestMode.hides(it) }, readsAloud = { readsAloud(ctx) })
    val call = HearthpaneCall(
        ready = { Calling.ready(ctx) }, selfName = { Calling.selfName(ctx) },
        peers = { CallingStore.peers.value }, busy = { Calling.state.value.busy },
        dial = { id, video -> onMain { Calling.dial(ctx, id, CallKind.CALL, video) } })
    return mapOf(
        HearthpaneAnnounce.NAME to announce::invoke,
        HearthpaneLists.NAME to lists::invoke,
        HearthpaneAlarms.NAME to alarms::invoke,
        HearthpaneBriefing.NAME to briefing::invoke,
        HearthpaneCall.NAME to call::invoke,
    )
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

  /** Runs [block] on the main thread and waits briefly for its answer (false if it didn't run in time). */
  private fun onMain(block: () -> Boolean): Boolean {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val task = FutureTask(block)
    Handler(Looper.getMainLooper()).post(task)
    return runCatching { task.get(MAIN_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrElse {
      task.cancel(false) // never let a late action happen after we've answered that it didn't
      Log.w(TAG, "main-thread action didn't finish in time", it); false
    }
  }
}
