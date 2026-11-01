/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns a source through preparation and queue handoff; failure always releases it.
 * A normal handoff transfers responsibility to the receiver and its discard callback.
 * [release] is invoked at most once by this owner, outside any lock. Network callers must
 * dispatch teardown off Main. True means handoff completed, including synchronous discard.
 */
internal fun <S : Any, R : Any> prepareSlideshowSource(
    source: S,
    prepare: (S) -> R?,
    release: (S) -> Unit,
    handoff: (S, R, () -> Unit) -> Unit,
): Boolean {
  val released = AtomicBoolean()
  val discard = {
    if (released.compareAndSet(false, true)) release(source)
  }
  try {
    val prepared = prepare(source)
    if (prepared == null) {
      discard()
      return false
    }
    handoff(source, prepared, discard)
    return true
  } catch (error: Throwable) {
    try {
      discard()
    } catch (cleanup: Throwable) {
      if (cleanup !== error) error.addSuppressed(cleanup)
    }
    throw error
  }
}
