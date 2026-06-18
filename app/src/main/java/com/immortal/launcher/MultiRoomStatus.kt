/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Live, in-process status of the multi-room relay ([MultiRoomService]) for the Immortal
 * settings screen — so the user gets real feedback when they enable it or hit Apply
 * (Connecting → Connected) instead of a button that appears to do nothing. Backed by
 * Compose snapshot state, so reading [text] in a composable recomposes on change. The
 * service writes it on the main thread.
 */
object MultiRoomStatus {
  var text by mutableStateOf("")
    internal set

  /** Result of the last Music Assistant sign-in attempt (for the transport credentials),
   *  shown next to the username/password fields. */
  var maAuth by mutableStateOf("")
    internal set
}
