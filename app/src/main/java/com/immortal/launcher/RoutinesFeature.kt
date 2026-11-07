/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent

/**
 * Picture routines entry point for the integrator. [install] once from [ImmortalApp.onCreate]:
 * registers `/remote/routines` and re-arms the routine alarm (alarms don't survive reboot; boot
 * and clock-change receivers call [reschedule]). Home shows [RoutinesHomeWidget] for
 * [WIDGET_KIND] and overlays [RoutineHomeToast].
 */
internal object RoutinesFeature {
  const val WIDGET_KIND = "routines"
  const val ROUTE = "/remote/routines"

  fun install(context: Context) {
    RoutineEngine.init(context)
    val api = RoutinesApi(RoutineEngine.repo, RoutineEngine)
    RemoteFeatureRoutes.register(ROUTE) { _, method, body ->
      val (status, json) = api.handle(method, body)
      RemoteFeatureRoutes.json(status, json)
    }
    reschedule(context)
  }

  /** Re-derives runs and the next alarm from the clock (boot, time/zone change, app start). */
  fun reschedule(context: Context) {
    RoutineEngine.init(context)
    RoutineEngine.step()
  }

  /** For [ClockChangeAlarms]: only wall-clock events re-arm. */
  fun onClockChanged(context: Context, action: String?) {
    if (action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED) reschedule(context)
  }
}
