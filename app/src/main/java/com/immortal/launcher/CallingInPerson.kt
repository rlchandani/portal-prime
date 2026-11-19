/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * "Someone at this Portal must turn this on." The phone remote can inject taps, keys and text
 * through the accessibility service ([RemoteInput]), which look like touches to a screen. So
 * anything that lets this Portal answer by itself is refused while the phone remote has been
 * driving it recently: the person has to put the remote down and touch the Portal. The setup
 * screen also asks for a 2 s press-and-hold on the confirm button.
 */
internal object CallInPerson {
  /** No phone-remote input for this long before an arming change is accepted. */
  const val REMOTE_QUIET_MS = 2 * 60_000L
  /** The confirm button's press-and-hold. */
  const val HOLD_MS = 2_000L
  /** Routes that inject input on this Portal ([RemoteRoutes]). */
  val INPUT_PATHS = setOf("/remote/key", "/remote/text", "/remote/cursor", "/remote/tap", "/remote/swipe", "/remote/scroll",
      "/remote/pin", "/remote/launch", "/remote/preset")

  @Volatile private var lastRemoteInputMs = Long.MIN_VALUE / 2

  fun noteRemoteInput(nowMs: Long) { lastRemoteInputMs = nowMs }

  fun confirmed(nowMs: Long): Boolean = quietSince(lastRemoteInputMs, nowMs)

  /** Pure rule: [nowMs] is at least [REMOTE_QUIET_MS] after the last remote input. */
  fun quietSince(lastInputMs: Long, nowMs: Long): Boolean = nowMs - lastInputMs >= REMOTE_QUIET_MS
}
