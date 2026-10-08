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
  fun launchConfirmation(context: Context, confirmation: Intent?) {
    val intent = requireNotNull(confirmation) { "Missing installer confirmation intent" }
    // The owned-dream bridge records an intentional exit before finish(), suppressing frame
    // recovery and bypassing the configured tap-dismiss app for this selected destination.
    ImmortalDreamNavigation.dismissForNavigation()
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
}
