/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.runtime.mutableStateOf

/** UI-confined snapshot: unused domains stay unread; loaded values remain observable by Compose. */
internal class DeferredSettingsSnapshot<S>(private val read: () -> S) {
  // The state itself is created on first use, so no empty-to-loaded write is needed in composition.
  private val state = lazy(LazyThreadSafetyMode.NONE) { mutableStateOf(read()) }

  val value: S get() = state.value.value

  fun refreshIfLoaded() {
    if (state.isInitialized()) state.value.value = read()
  }
}
