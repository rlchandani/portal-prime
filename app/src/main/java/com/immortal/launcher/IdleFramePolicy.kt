/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Whether Home's own idle timer ([IdleFrameTimer]) opens the photo frame as Hearthpane's Activity, just before the
 * system screen timeout would have started [PhotoDreamService]. Same frame, same controller: the Activity is the
 * long-running frame [DreamPolicy] already hands over to ~2 minutes into every dream, only started earlier. The
 * reason is the mic: on Android 10 only the app whose Activity is in front hears the room, so "Hey Jarvis" works on
 * this frame and never on the dream.
 *
 * Every case where the old dream would not have run, or would not have stayed, skips and leaves the system path
 * exactly as before (the dream stays the fallback): the screensaver off or not ours, the overnight window, a frame
 * that would not hold the screen (presence mode, a Go on battery: the dream ↔ sleep decision must stay Meta's), a
 * room Meta reports empty (the system would sleep), anything on Home keeping the screen awake (the system would
 * never time out), or another screen owner. Pure, so every rule is JVM-tested; order in [decide] = the log reason.
 */
internal object IdleFramePolicy {
  data class Facts(
      /** Hearthpane's screensaver is on ([ScreensaverConfig.Settings.enabled]). */
      val screensaverEnabled: Boolean,
      /** The system dream is enabled and is [PhotoDreamService] (read, never written here). */
      val systemDreamIsOurs: Boolean,
      /** Inside the overnight window: [SleepScheduler] owns the screen (dark or the night clock). */
      val overnight: Boolean,
      /** [DreamPolicy.holdScreenOn]: the frame pins the display, so it would become permanent anyway. */
      val frameHoldsScreen: Boolean,
      /** Meta's presence CV confidently reports nobody in the room: the system would sleep, not dream. */
      val roomEmpty: Boolean,
      val screenInteractive: Boolean,
      /** A system dream is already showing (it started first; it stays). */
      val dreaming: Boolean,
      /** Home is the resumed Activity with window focus: no dialog, no other screen over it. */
      val homeFocused: Boolean,
      /** Something on Home holds the screen awake (playing music, the Apps page, a tool): no timeout would come. */
      val homeKeepsScreenOn: Boolean,
      /** A routine, an install confirmation or a YouTube video owns the screen. */
      val screenOwnedElsewhere: Boolean,
  )

  enum class Skip(val log: String) {
    SCREENSAVER_OFF("screensaver is off"),
    NOT_OUR_DREAM("the system screensaver isn't Hearthpane's"),
    OVERNIGHT("overnight window"),
    NOT_HELD("the frame wouldn't hold the screen; the Portal's presence policy decides"),
    ROOM_EMPTY("nobody is in the room"),
    SCREEN_OFF("screen off"),
    DREAMING("the system screensaver is already showing"),
    NOT_FOCUSED("Home isn't the focused screen"),
    KEPT_AWAKE("Home is keeping the screen awake"),
    SCREEN_OWNED("another screen owns the display"),
  }

  sealed interface Decision {
    data object Launch : Decision
    data class Wait(val skip: Skip) : Decision
  }

  fun decide(f: Facts): Decision {
    val skip = when {
      !f.screensaverEnabled -> Skip.SCREENSAVER_OFF
      !f.systemDreamIsOurs -> Skip.NOT_OUR_DREAM
      f.overnight -> Skip.OVERNIGHT
      !f.frameHoldsScreen -> Skip.NOT_HELD
      f.roomEmpty -> Skip.ROOM_EMPTY
      !f.screenInteractive -> Skip.SCREEN_OFF
      f.dreaming -> Skip.DREAMING
      !f.homeFocused -> Skip.NOT_FOCUSED
      f.homeKeepsScreenOn -> Skip.KEPT_AWAKE
      f.screenOwnedElsewhere -> Skip.SCREEN_OWNED
      else -> null
    }
    return skip?.let { Decision.Wait(it) } ?: Decision.Launch
  }

  /** Meta's CV is authoritative only when it is the confident source and says absent. */
  fun roomEmpty(presence: PresenceState): Boolean = presence.confident && presence.presence == Presence.ABSENT
}

/**
 * When the idle frame opens: [LEAD_MS] before the system screen timeout. Android dims the screen up to 7 s before
 * it times out, so 10 s keeps the frame ahead of the dim (no dim-then-brighten flicker) and of the dream. Pure.
 */
internal object IdleFrameTiming {
  const val LEAD_MS = 10_000L
  /** Below this the timeout is too short to lead by [LEAD_MS]: open at half of it instead. */
  private const val MIN_TIMEOUT_MS = 2 * LEAD_MS
  /** `screen_off_timeout` values at or above a day mean "never" (Android uses Int.MAX_VALUE). */
  private const val NEVER_MS = 24L * 60 * 60_000

  /** Idle time after which Home opens the frame, or null when the screen never times out. */
  fun idleDelayMs(screenOffTimeoutMs: Long): Long? = when {
    screenOffTimeoutMs <= 0L || screenOffTimeoutMs >= NEVER_MS -> null
    screenOffTimeoutMs < MIN_TIMEOUT_MS -> screenOffTimeoutMs / 2
    else -> screenOffTimeoutMs - LEAD_MS
  }

  /** How much longer to wait, given the last interaction [idleSinceMs] ago; zero means open now. */
  fun remainingMs(delayMs: Long, idleSinceMs: Long): Long = (delayMs - idleSinceMs).coerceAtLeast(0L)
}
