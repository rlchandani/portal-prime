/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Jarvis asked Hearthpane to point at a control (`hearthpane.highlight`): the screen showing it pulses it with an
 * ember ring for [SHOW_MS]. Set from the tool provider's binder thread; screens collect [current] and draw.
 */
internal object JarvisHighlight {
  const val SHOW_MS = 6_000L

  /** One request: [seq] makes a repeat of the same target pulse again. */
  data class Request(val target: String, val hint: String?, val seq: Long)

  private val seqs = AtomicLong()
  private val mutable = MutableStateFlow<Request?>(null)
  val current: StateFlow<Request?> = mutable.asStateFlow()

  fun show(target: String, hint: String?): Request = Request(target, hint, seqs.incrementAndGet()).also { mutable.value = it }

  /** Clear [request] once it has shown, unless a newer one replaced it. */
  fun done(request: Request) { mutable.compareAndSet(request, null) }
}
