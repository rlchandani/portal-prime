/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.SecureRandom

/**
 * Feature-scope dependencies for linking homes, made once by [CallsActivity] and passed down:
 * one [SecureRandom] (never created inside composition, where `remember` would make a new one on
 * every re-entry and hide it from tests) and a clock.
 */
internal class HomeLinkFeature(val random: SecureRandom, val clock: () -> Long = System::currentTimeMillis) {
  companion object {
    /** The one generator for the feature's process-wide parts (store, sync); screens get it via [HomeLinkFeature]. */
    val sharedRandom: SecureRandom by lazy { SecureRandom() }
  }
}
