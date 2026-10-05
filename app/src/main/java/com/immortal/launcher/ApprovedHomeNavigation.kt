/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.immortal.launcher.ui.components.launchSafely

/** Preserve the native editor Activity while selecting its approved page/category explicitly. */
internal fun Context.openApprovedHomeSettings(page: String) {
  runCatching {
    startActivity(approvedSettingsIntent(this, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
}

/** External entry points select the retained Apps tab in the existing launcher task. */
internal fun Context.openApprovedHomeApps() {
  runCatching {
    startActivity(Intent(this, HomeActivity::class.java)
        .putExtra("approved_home_tab", "apps")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
  }
}

/** Tools has no timer deep link. Host its existing page without changing timer logic or storage. */
@Composable
internal fun ApprovedHomeTimersPage(onDismiss: () -> Unit) {
  if (!LocalApprovedLauncherPageActive.current || !LocalApprovedWidgetPageInput.current) return
  val context = LocalContext.current
  Dialog(onDismissRequest = onDismiss,
      properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    val view = LocalView.current
    DisposableEffect(view) {
      (view.parent as? DialogWindowProvider)?.window?.let { window ->
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
          hide(WindowInsetsCompat.Type.systemBars())
          systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
      }
      onDispose { }
    }
    CompositionLocalProvider(LocalApprovedToolPage provides true) {
      ApprovedAppsShell("tools", onExit = onDismiss) {
        TimersOverlay {
          onDismiss()
          context.launchSafely(ToolsActivity::class.java)
        }
      }
    }
  }
}
