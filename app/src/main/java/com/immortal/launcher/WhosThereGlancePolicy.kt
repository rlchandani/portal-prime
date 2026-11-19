/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * When Hearthpane may open the camera for a glance. Pure: every input is a value, so the rules
 * the mock and the camera spike set are tested on the JVM:
 * - only with recognition switched on by an adult, someone enrolled, models loaded and CAMERA
 *   granted;
 * - only from Home in front with the screen awake (the camera can't open from the background);
 * - never during a call, never in quiet hours (operator debug override aside), never while the
 *   household paused recognition;
 * - at most one glance per [COOLDOWN_MS], and not again right after someone was recognised.
 */
internal enum class GlanceTrigger {
  /** Meta presence reported a new arrival. */
  ARRIVAL,
  /** The screen woke into Home while Meta presence says someone is here. */
  WAKE,
  /** Someone tapped the indicator ("look again"). */
  MANUAL,
}

internal enum class GlanceSkip(val log: String) {
  OFF("recognition off"),
  NOBODY_ENROLLED("nobody enrolled"),
  MODELS_UNAVAILABLE("models unavailable"),
  NO_PERMISSION("camera permission missing"),
  PAUSED("paused"),
  QUIET_HOURS("quiet hours"),
  IN_CALL("in a call"),
  NOT_FOREGROUND("home not in front"),
  SCREEN_OFF("screen not awake"),
  BUSY("glance or enrolment already running"),
  COOLDOWN("cooldown"),
  RECENTLY_RECOGNISED("recognised moments ago"),
}

internal data class GlanceInputs(
    val trigger: GlanceTrigger,
    val nowMs: Long,
    val enabled: Boolean,
    val enrolled: Int,
    val modelsReady: Boolean,
    val cameraGranted: Boolean,
    val pausedUntilMs: Long,
    val quietHours: Boolean,
    val debugIgnoreQuiet: Boolean,
    val inCall: Boolean,
    val homeInFront: Boolean,
    val screenAwake: Boolean,
    val cameraBusy: Boolean,
    val lastGlanceEndMs: Long,
    val lastRecognisedMs: Long,
)

internal sealed interface GlanceDecision {
  data object Go : GlanceDecision
  data class Skip(val reason: GlanceSkip) : GlanceDecision
}

internal object WhosThereGlancePolicy {
  const val COOLDOWN_MS = 20_000L
  const val MANUAL_COOLDOWN_MS = 3_000L
  const val RECOGNISED_HOLD_MS = 20_000L
  /** Coming back to Home after this long away (dream, screen off) counts as a wake. */
  const val RETURN_AFTER_MS = 60_000L

  fun decide(i: GlanceInputs): GlanceDecision {
    val skip = firstSkip(i) ?: return GlanceDecision.Go
    return GlanceDecision.Skip(skip)
  }

  private fun firstSkip(i: GlanceInputs): GlanceSkip? = when {
    !i.enabled -> GlanceSkip.OFF
    i.enrolled <= 0 -> GlanceSkip.NOBODY_ENROLLED
    !i.modelsReady -> GlanceSkip.MODELS_UNAVAILABLE
    !i.cameraGranted -> GlanceSkip.NO_PERMISSION
    i.pausedUntilMs > i.nowMs -> GlanceSkip.PAUSED
    i.quietHours && !i.debugIgnoreQuiet -> GlanceSkip.QUIET_HOURS
    i.inCall -> GlanceSkip.IN_CALL
    !i.homeInFront -> GlanceSkip.NOT_FOREGROUND
    !i.screenAwake -> GlanceSkip.SCREEN_OFF
    i.cameraBusy -> GlanceSkip.BUSY
    i.nowMs - i.lastGlanceEndMs < cooldown(i.trigger) -> GlanceSkip.COOLDOWN
    i.trigger != GlanceTrigger.MANUAL && i.nowMs - i.lastRecognisedMs < RECOGNISED_HOLD_MS ->
      GlanceSkip.RECENTLY_RECOGNISED
    else -> null
  }

  /** Home came back after [awayMs] (dream ended, screen woke): a wake glance if someone is here. */
  fun onReturn(awayMs: Long, presence: PresenceState): GlanceTrigger? =
      if (awayMs >= RETURN_AFTER_MS && presence.presence == Presence.PRESENT && presence.confident) GlanceTrigger.WAKE else null

  private fun cooldown(trigger: GlanceTrigger): Long =
      if (trigger == GlanceTrigger.MANUAL) MANUAL_COOLDOWN_MS else COOLDOWN_MS

  /**
   * Is this presence change a new arrival worth a glance? A real ABSENT→PRESENT (or a long
   * UNKNOWN→PRESENT, per [FollowMeArrival]) with the screen awake, or the screen waking into
   * Home while Meta presence already says someone is here.
   */
  fun trigger(previous: PresenceState, next: PresenceState, nowMs: Long): GlanceTrigger? {
    val awake = next.screen == ScreenState.INTERACTIVE
    if (!awake) return null
    val prevLocal = FollowMeArrival.local(previous.presence, previous.confident)
    val nextLocal = FollowMeArrival.local(next.presence, next.confident)
    if (FollowMeArrival.isArrival(prevLocal, previous.sinceMs, nextLocal, nowMs)) return GlanceTrigger.ARRIVAL
    val woke = previous.screen != ScreenState.INTERACTIVE
    return if (woke && nextLocal == Presence.PRESENT) GlanceTrigger.WAKE else null
  }
}
