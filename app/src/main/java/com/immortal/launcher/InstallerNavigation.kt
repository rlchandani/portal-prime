/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent

/** Called on the receiver's main thread for a headless install's visible confirmation. */
internal object InstallerNavigation {
  /**
   * NEW_TASK alone lets Android bring an EXISTING installer task to the front without
   * delivering the new intent. A dialog left behind by an abandoned session (e.g. one shown
   * under the dream that timed out) then gets confirmed instead of ours: the tap goes to a dead
   * session and the new one never shows — the 1.257 stall. CLEAR_TASK replaces that task.
   */
  const val CONFIRMATION_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

  fun launchConfirmation(context: Context, confirmation: Intent?, keepScreenOn: () -> Unit = {}) {
    val intent = requireNotNull(confirmation) { "Missing installer confirmation intent" }
    // The owned-dream bridge records an intentional exit before finish(), suppressing frame
    // recovery and bypassing the configured tap-dismiss app for this selected destination.
    ImmortalDreamNavigation.dismissForNavigation()
    // Then wake from any dream we don't own (or one still starting) and keep it from returning.
    keepScreenOn()
    context.startActivity(intent.addFlags(CONFIRMATION_FLAGS))
  }
}
