/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hearthpane's own microphone users (the intercom's broadcast, a voice memo) claim the mic here before they open it,
 * so "Hey Jarvis" listening steps aside for them. Other apps need no claim: one that is in front pauses Hearthpane's
 * screens anyway, and one in the background is silenced by Android while Hearthpane is the top app. Calls and the
 * camera are watched separately ([WakePolicy.Facts.callActive], [WakePolicy.Facts.cameraInUse]). Any thread.
 */
internal object MicClaims {
  private val held = AtomicInteger()

  /** Called when the mic goes from free to claimed and back ([HearthpaneWake] re-decides). Any thread. */
  @Volatile var onChange: () -> Unit = {}

  /** One feature's hold on the mic; [close] (idempotent) gives it back. */
  class Claim internal constructor() : Closeable {
    private val open = AtomicBoolean(true)

    override fun close() {
      if (open.compareAndSet(true, false) && held.decrementAndGet() == 0) onChange()
    }
  }

  val active: Boolean get() = held.get() > 0

  /** Claim the mic before opening it; listening stops at once (the decision runs on the main thread). */
  fun claim(): Claim {
    if (held.incrementAndGet() == 1) onChange()
    return Claim()
  }
}
