/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Personal reminders entry point: `RemindersFeature.install(this)` from [ImmortalApp.onCreate],
 * after the household bus and Who's there? start. Registers `/remote/reminders` (phone) and the
 * `reminders_rev` signal, loads the replica, announces it (the catch-up path for a Portal that was
 * off) and starts [ReminderAlerts]. UI and Jarvis edit through the helpers below (any thread but
 * main: they write the store).
 */
internal object RemindersFeature {
  private const val TAG = "HearthpaneReminders"
  @Volatile private var installed = false
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  fun install(context: Context) {
    if (installed) return
    installed = true
    val app = context.applicationContext
    ReminderStore.setListener { ctx, _, _, _ ->
      ReminderSync.signal(ctx)
      ReminderAlerts.onChanged()
    }
    RemoteFeatureRoutes.register(RemindersRoute.PATH, RemindersRoute)
    HouseholdBus.register(ReminderPeerAuth.SIGNAL_TYPE) { ctx, message -> ReminderSync.onSignal(ctx, message) }
    runCatching { ReminderStore.ensureLoaded(app) }.onFailure { Log.w(TAG, "could not load reminders", it) }
    ReminderSync.signal(app)
    ReminderAlerts.start(app)
    scope.launch { WhosThere.state.collect { ReminderAlerts.onWhoChanged(it) } }
  }

  fun add(context: Context, draft: ReminderDraft, createdBy: String, source: ReminderStore.Source = ReminderStore.Source.LOCAL): ReminderResult {
    val by = ChoresFeature.selfId(context) ?: return ReminderResult.Refused("companion_storage_unavailable")
    return ReminderStore.apply(context, source) { s ->
      ReminderOps.add(s, ChoresFeature.newId(), draft, createdBy, by, ReminderStore.clock())
    }
  }

  fun edit(context: Context, id: String, draft: ReminderDraft): ReminderResult {
    val by = ChoresFeature.selfId(context) ?: return ReminderResult.Refused("companion_storage_unavailable")
    return ReminderStore.apply(context, ReminderStore.Source.LOCAL) { s -> ReminderOps.edit(s, id, draft, by, ReminderStore.clock()) }
  }

  fun setDone(context: Context, id: String, done: Boolean, source: ReminderStore.Source = ReminderStore.Source.LOCAL): ReminderResult {
    val by = ChoresFeature.selfId(context) ?: return ReminderResult.Refused("companion_storage_unavailable")
    return ReminderStore.apply(context, source) { s -> ReminderOps.setDone(s, id, done, by, ReminderStore.clock()) }
  }

  fun delete(context: Context, id: String): ReminderResult {
    val by = ChoresFeature.selfId(context) ?: return ReminderResult.Refused("companion_storage_unavailable")
    return ReminderStore.apply(context, ReminderStore.Source.LOCAL) { s -> ReminderOps.delete(s, id, by, ReminderStore.clock()) }
  }

  /** "Kitchen" (this Portal's name) for reminders made on the Portal itself. */
  fun creator(context: Context, person: WhosTherePerson?): String = person?.name ?: HouseholdListSync.selfName(context)
}
