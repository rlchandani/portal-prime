/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Plain-JVM mirror of whether a routine owns the screen, kept in sync by [RoutineEngine]. Code that
 * must not load the engine (it touches the main Looper at init), such as [DreamPolicy], reads this.
 */
internal object RoutineScreenState {
  /** A routine (or preview) is running, whether or not its takeover is in front yet. */
  @Volatile var running = false
  /** The takeover Activity is started. */
  @Volatile var visible = false

  fun ownsScreen(): Boolean = running || visible
}
