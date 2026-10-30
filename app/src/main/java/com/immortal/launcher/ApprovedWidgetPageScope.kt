/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Retain neighboring widget pages while pausing their producers and resume hooks.
 * The enclosing tab still owns its input gate; a child must not mutate that gate
 * when it becomes inactive, or it would disable the selected sibling as well.
 */
@Composable
internal fun ApprovedWidgetPageScope(
    active: Boolean,
    content: @Composable () -> Unit,
) {
  val selected = LocalApprovedLauncherPageActive.current && active
  val tabInput = LocalApprovedLauncherInputGate.current
  ApprovedLauncherPageLifecycle(selected) {
    CompositionLocalProvider(
        LocalApprovedWidgetPageInput provides selected,
        LocalApprovedLauncherInputGate provides tabInput,
        content = content,
    )
  }
}
