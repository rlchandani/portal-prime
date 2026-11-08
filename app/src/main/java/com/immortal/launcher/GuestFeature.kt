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
import com.immortal.launcher.settings.GuestSettings
import java.time.ZoneId
import org.json.JSONObject

/**
 * Guest card wiring: persists [GuestMode] across restarts, ends it on schedule, and owns the
 * `/remote/guests` route. Installed once from [ImmortalApp].
 */
internal object GuestFeature {
  const val ROUTE = "/remote/guests"
  private const val PREFS = "immortal_guest_mode"
  private const val KEY_STATE = "state"
  private const val TAG = "ImmortalGuest"
  /** Re-check at least this often, so a long schedule survives clock changes. */
  private const val MAX_TIMER_MS = 60 * 60 * 1000L

  private val main by lazy { Handler(Looper.getMainLooper()) }
  @Volatile private var app: Context? = null
  private val expiry = Runnable { app?.let(::checkExpiry) }

  fun install(context: Context) {
    val appContext = context.applicationContext
    app = appContext
    GuestMode.set(GuestModeState.decode(prefs(appContext).getString(KEY_STATE, null)))
    checkExpiry(appContext)
    val api = GuestApi(AndroidPort(appContext))
    RemoteFeatureRoutes.register(ROUTE) { _, method, body ->
      val (status, json) = synchronized(this) { api.handle(method, body) }
      RemoteFeatureRoutes.json(status, json)
    }
  }

  fun start(context: Context, schedule: GuestSchedule) =
      save(context, GuestModeState.start(schedule, System.currentTimeMillis(), ZoneId.systemDefault()))

  fun end(context: Context) = save(context, GuestModeState.OFF)

  @Synchronized private fun save(context: Context, state: GuestModeState) {
    prefs(context).edit().putString(KEY_STATE, state.encode()).apply()
    val wasOn = GuestMode.isOn()
    GuestMode.set(state)
    // A screensaver already showing household photos can't swap sources in place: end it so the
    // next one starts neutral (GuestPolicy). Ending Guest mode leaves the current one running.
    if (!wasOn && state.isOn(System.currentTimeMillis())) main.post { ImmortalDreamNavigation.dismissForNavigation() }
    Log.i(TAG, "guest mode ${if (state.active) "on until=${state.untilMs ?: "ended"}" else "off"}")
    arm(state)
  }

  private fun checkExpiry(context: Context) {
    val state = GuestMode.state.value
    if (state.active && !state.isOn(System.currentTimeMillis())) save(context, GuestModeState.OFF) else arm(state)
  }

  private fun arm(state: GuestModeState) {
    main.removeCallbacks(expiry)
    val until = state.untilMs ?: return
    if (!state.active) return
    val wait = (until - System.currentTimeMillis()).coerceIn(0L, MAX_TIMER_MS)
    main.postDelayed(expiry, wait)
  }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** The card as the registry sees it, the password only through [GuestPasswordStore]. */
  private class AndroidPort(private val context: Context) : GuestPort {
    override fun card(): JSONObject = GuestSettings.domain.flatJson(context)
    override fun applyCard(values: JSONObject) { GuestSettings.domain.apply(context, values) }
    override fun hasPassword() = GuestPasswordStore.get(context).isSet()
    override fun setPassword(value: String) = GuestPasswordStore.get(context).write(value)
    override fun mode() = GuestMode.state.value
    override fun start(schedule: GuestSchedule) = start(context, schedule)
    override fun end() = end(context)
    override fun nowMs() = System.currentTimeMillis()
  }
}
