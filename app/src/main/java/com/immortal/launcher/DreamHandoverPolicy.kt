/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Whether a starting [PhotoDreamService] hands the screen to [PhotoFramePreviewActivity] at once instead of ~2
 * minutes later, when the Portal force-wakes the dream ([DreamPolicy]). The case Home's [IdleFrameTimer] cannot
 * cover: Meta wakes a dark screen straight into the dream (someone walks in), or the dream starts over another
 * screen. On Android 10 the mic is silenced behind a dream, so until the handover "Hey Jarvis" is deaf.
 *
 * The dream stays (today's behaviour, and the 2-minute continuation) in exactly the cases [IdleFramePolicy] leaves to
 * it: the rules are its own, evaluated with the Home-only facts fixed (the dream is ours and running; Home focus and
 * Home's keep-awake don't apply). Two dream-only owners also keep it: a pinned app under the dream (its curtain, not
 * an Activity, must cover it) and a Calls or Jarvis handoff in flight. Pure, so every rule is JVM-tested.
 */
internal object DreamHandoverPolicy {
  data class Facts(
      val screensaverEnabled: Boolean,
      val overnight: Boolean,
      /** [DreamPolicy.holdScreenOn]: the frame pins the display, so the 2-minute continuation would be permanent. */
      val frameHoldsScreen: Boolean,
      val roomEmpty: Boolean,
      val screenInteractive: Boolean,
      /** A routine, an install confirmation or a YouTube video owns the screen. */
      val screenOwnedElsewhere: Boolean,
      /** A pinned app is under this dream ([PinnedAppScreensaver]). */
      val pinned: Boolean,
      /** A Calls or Jarvis bridge is in flight ([DreamPolicy.inStockHandoff], [DreamPolicy.bridgeAt]). */
      val bridged: Boolean,
  )

  enum class Skip(val log: String) {
    PINNED("a pinned app is under the dream"),
    BRIDGED("a Calls or Jarvis handoff is in flight"),
  }

  sealed interface Decision {
    data object HandOver : Decision
    /** A rule shared with Home's idle frame: the dream stays for exactly that reason. */
    data class Shared(val skip: IdleFramePolicy.Skip) : Decision
    /** A dream-only owner the frame Activity must not cover. */
    data class Owned(val skip: Skip) : Decision
  }

  fun log(d: Decision): String = when (d) {
    Decision.HandOver -> "handing over to the frame"
    is Decision.Shared -> d.skip.log
    is Decision.Owned -> d.skip.log
  }

  fun decide(f: Facts): Decision {
    val shared = IdleFramePolicy.decide(idleFacts(f))
    if (shared is IdleFramePolicy.Decision.Wait) return Decision.Shared(shared.skip)
    return when {
      f.pinned -> Decision.Owned(Skip.PINNED)
      f.bridged -> Decision.Owned(Skip.BRIDGED)
      else -> Decision.HandOver
    }
  }

  /** The idle frame's own facts as the running dream sees them. */
  internal fun idleFacts(f: Facts) = IdleFramePolicy.Facts(
      screensaverEnabled = f.screensaverEnabled,
      systemDreamIsOurs = true,
      overnight = f.overnight,
      frameHoldsScreen = f.frameHoldsScreen,
      roomEmpty = f.roomEmpty,
      screenInteractive = f.screenInteractive,
      dreaming = false,
      homeFocused = true,
      homeKeepsScreenOn = false,
      screenOwnedElsewhere = f.screenOwnedElsewhere,
  )

  /** A bridge stamped this recently still suppresses recovery ([classifyDreamStop]). */
  fun bridged(inStockHandoff: Boolean, bridgeAgoMs: Long): Boolean =
      inStockHandoff || bridgeAgoMs in 0..BRIDGE_GRACE_MS
}

/** How long a handover waits, and holds the screen awake across the dream's end. Pure constants. */
internal object DreamHandoverTiming {
  /** No first frame from the Activity by then: the dream shows the frame itself, as before. */
  const val FIRST_FRAME_TIMEOUT_MS = 3_000L
  /**
   * The Activity's keep-screen-on is ignored while the dream covers it, so when the dream ends the system would
   * otherwise see nothing keeping the screen awake and sleep it. A brief screen wake lock bridges that instant.
   */
  const val WAKE_BRIDGE_MS = 3_000L
}
