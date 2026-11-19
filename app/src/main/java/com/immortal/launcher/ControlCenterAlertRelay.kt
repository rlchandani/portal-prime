/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log

/**
 * Presents the background alert [CcBackgroundPolicy.Plan] through [ControlCenterBanners]. Takeovers
 * are also told to the household (`cc_alert`), so a Portal without its own Home Assistant socket
 * still takes over; [CcAlertGate] shows each one once per Portal and applies the cooldowns. The open
 * banner's hourly limit is saved, so a restart (or an install) can't repeat it. Dry run uses its own
 * in-memory gate, logs every decision and shows, plays and sends nothing.
 */
internal object CcAlertRelay {
  private const val TAG = "HearthpaneBanner"
  private const val PREFS = "hearthpane_cc_alert_gate"
  private const val KEY_OPEN_AT = "open_banner_at"

  @Volatile internal var clock: () -> Long = System::currentTimeMillis
  private val lock = Any()
  private var gate: CcAlertGate? = null
  private var dryGate = CcAlertGate()

  fun install(context: Context) {
    HouseholdBus.register(CcAlertWire.TYPE) { ctx, message -> onHousehold(ctx, message) }
  }

  /** From the engine thread after each live publish. */
  fun present(context: Context, plan: CcBackgroundPolicy.Plan, settings: ControlCenterConfig.Settings, appVisible: Boolean, dryRun: Boolean) {
    val app = context.applicationContext
    val now = clock()
    plan.takeovers.forEach { alert ->
      if (!admit(app, dryRun) { it.admitTakeover(alert, now) }) return@forEach
      if (dryRun) Log.i(TAG, "dry-run relay cc_alert class=${alert.deviceClass ?: "-"} (not sent)")
      else HouseholdBus.send(app, CcAlertWire.TYPE, CcAlertWire.encode(alert))
      ControlCenterBanners.offer(app, alert, settings, appVisible, dryRun)
    }
    if (plan.openFresh.isNotEmpty()) presentOpen(app, plan.openActive, settings, appVisible, dryRun, now)
    plan.info.forEach { ControlCenterBanners.offer(app, it, settings, appVisible, dryRun) }
  }

  private fun presentOpen(app: Context, open: List<CcAlert>, settings: ControlCenterConfig.Settings, appVisible: Boolean, dryRun: Boolean, now: Long) {
    val banner = CcBackgroundPolicy.consolidate(open, now) ?: return
    val decision = CcBannerPolicy.decide(banner, settings, ControlCenterBanners.quietNow(app))
    // Not shown here (not a shared room, warnings off, or the Control Center is on screen): keep the hour.
    if (decision.show == CcBannerPolicy.Show.NONE || appVisible) {
      if (dryRun) Log.i(TAG, "dry-run open banner n=${open.size} show=none sharedRoom=${settings.sharedRoom} appVisible=$appVisible")
      return
    }
    if (!admit(app, dryRun) { it.admitOpen(now) }) {
      if (dryRun) Log.i(TAG, "dry-run open banner n=${open.size} cooldown")
      return
    }
    if (dryRun) Log.i(TAG, "dry-run open banner n=${open.size} title=\"${banner.title}\"")
    ControlCenterBanners.offer(app, banner, settings, appVisible, dryRun)
  }

  private fun onHousehold(context: Context, message: HouseholdMessage) {
    val alert = CcAlertWire.decode(message.body) ?: return
    val app = context.applicationContext
    if (!admit(app, dryRun = false) { it.admitTakeover(alert, clock()) }) return
    Log.i(TAG, "control center takeover from another room")
    ControlCenterBanners.offer(app, alert, ControlCenterConfig.load(app), appVisible = false, dryRun = CcDebugStore.dryRun(app))
  }

  private fun admit(app: Context, dryRun: Boolean, step: (CcAlertGate) -> CcAlertGate.Result): Boolean = synchronized(lock) {
    if (dryRun) {
      val r = step(dryGate); dryGate = r.gate
      if (!r.show) Log.i(TAG, "dry-run gate ${r.reason.name.lowercase()}")
      return r.show
    }
    val current = gate ?: CcAlertGate(openBannerAt = prefs(app).getLong(KEY_OPEN_AT, 0L))
    val r = step(current)
    gate = r.gate
    if (r.gate.openBannerAt != current.openBannerAt) prefs(app).edit().putLong(KEY_OPEN_AT, r.gate.openBannerAt).apply()
    r.show
  }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
