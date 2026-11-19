/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The one top-right "who's there" spot (mock: Indicator system). Android 10 has no system camera
 * dot, so [Looking] is shown for the whole time the camera is open and cannot be hidden.
 */
internal sealed interface WhosThereIndicator {
  /** Recognition off on this Portal: nothing shown, the camera is never opened. */
  data object Hidden : WhosThereIndicator
  /** On, camera not in use. */
  data object Ready : WhosThereIndicator
  /** Camera open for the glance (or the enrolment capture). */
  data object Looking : WhosThereIndicator
  data class Recognised(val personId: String, val unsure: Boolean, val othersNear: Boolean) : WhosThereIndicator
  data class Family(val personIds: List<String>) : WhosThereIndicator
  data class Paused(val untilMs: Long) : WhosThereIndicator
  data object InCall : WhosThereIndicator
  data class QuietHours(val untilMinute: Int) : WhosThereIndicator
  data object TooDark : WhosThereIndicator
  data object CameraCovered : WhosThereIndicator
  /** Models failed to load or the device can't run them: degrade to off, with a reason. */
  data class Unavailable(val message: String) : WhosThereIndicator
}

internal data class IndicatorInputs(
    val enabled: Boolean,
    val enrolled: Int,
    val modelsError: String?,
    val looking: Boolean,
    val view: WhosThereView,
    val pausedUntilMs: Long,
    val inCall: Boolean,
    val quietHours: Boolean,
    val debugIgnoreQuiet: Boolean,
    val quietEndMinute: Int,
    val lastGlanceDark: Boolean,
    val lastGlanceCovered: Boolean,
    val nowMs: Long,
)

internal object WhosThereIndicatorPolicy {
  fun of(i: IndicatorInputs): WhosThereIndicator = when {
    !i.enabled || i.enrolled <= 0 -> WhosThereIndicator.Hidden
    i.modelsError != null -> WhosThereIndicator.Unavailable(i.modelsError)
    // Honesty first: if the camera is open, say so, whatever else is true.
    i.looking -> WhosThereIndicator.Looking
    i.pausedUntilMs > i.nowMs -> WhosThereIndicator.Paused(i.pausedUntilMs)
    i.view is WhosThereView.Personal ->
      WhosThereIndicator.Recognised(i.view.personId, i.view.confirm, i.view.othersNear)
    i.view is WhosThereView.Family -> WhosThereIndicator.Family(i.view.personIds)
    i.inCall -> WhosThereIndicator.InCall
    i.quietHours && !i.debugIgnoreQuiet -> WhosThereIndicator.QuietHours(i.quietEndMinute)
    i.lastGlanceCovered -> WhosThereIndicator.CameraCovered
    i.lastGlanceDark -> WhosThereIndicator.TooDark
    else -> WhosThereIndicator.Ready
  }

  /** "6:30" / "18:30" style label for the quiet-hours end. */
  fun clock(minute: Int, use24: Boolean): String {
    val m = ((minute % 1440) + 1440) % 1440
    val h = m / 60
    val mm = (m % 60).toString().padStart(2, '0')
    return if (use24) "$h:$mm" else "${if (h % 12 == 0) 12 else h % 12}:$mm"
  }
}
