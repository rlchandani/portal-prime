/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

/**
 * When the small ember "listening" dot shows on Home and the photo frame: only while [WakePolicy] says listen *and*
 * the mic session is really running. Quiet hours, the off switch, a mute, a call or Jarvis opening all pause the
 * policy, so the dot is gone in each of them; a mic that failed to open hides it too. Pure.
 */
internal object WakeIndicator {
  fun shown(decision: WakePolicy.Decision?, micActive: Boolean): Boolean =
      decision == WakePolicy.Decision.Listen && micActive
}
