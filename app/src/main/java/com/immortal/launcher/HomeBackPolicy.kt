/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** What a Back press on the launcher does. The launcher root never finishes on Back. */
internal enum class HomeBackAction { CLOSE_MUSIC, GO_HOME_TAB, CLEAR_DRAFT, EXIT_EDIT, CONSUME }

/**
 * Back on HomeActivity, innermost layer first. With nothing left to close it is consumed:
 * on Android 10 an unhandled Back finishes the task-root HOME activity, and the device
 * falls through to Meta's home.
 */
internal object HomeBackPolicy {
  fun resolve(
      musicOpen: Boolean,
      onHomeTab: Boolean,
      hasDraft: Boolean,
      editMode: Boolean,
  ): HomeBackAction = when {
    musicOpen -> HomeBackAction.CLOSE_MUSIC
    !onHomeTab -> HomeBackAction.GO_HOME_TAB
    hasDraft -> HomeBackAction.CLEAR_DRAFT
    editMode -> HomeBackAction.EXIT_EDIT
    else -> HomeBackAction.CONSUME
  }
}
