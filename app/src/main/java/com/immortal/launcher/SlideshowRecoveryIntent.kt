/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Main-thread intent for one album recovery; an explicit request wins over autoplay. */
internal class SlideshowRecoveryIntent {
  var resolving = false
    private set
  private var automatic = true

  fun request(automatic: Boolean): Boolean {
    if (resolving) {
      this.automatic = this.automatic && automatic
      return false
    }
    this.automatic = automatic
    resolving = true
    return true
  }

  fun finish(): Boolean {
    resolving = false
    return automatic
  }
}
