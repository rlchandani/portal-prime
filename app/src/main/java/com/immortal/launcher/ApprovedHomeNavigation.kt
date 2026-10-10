/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.BackHandler
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

internal const val APPROVED_HOME_TAB = "approved_home_tab"
internal const val APPROVED_HOME_SEARCH = "approved_home_search"

private tailrec fun Context.approvedNavigationActivity(): Activity? = when {
  this is Activity -> this
  this is ContextWrapper && baseContext !== this -> baseContext.approvedNavigationActivity()
  else -> null
}

/** Internal navigation keeps the caller's task; services still need NEW_TASK. */
internal fun Context.startApprovedInternalActivity(intent: Intent) {
  if (approvedNavigationActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  startActivity(intent)
}

/** Open an ordinary Settings main-pane page within the caller's task. */
internal fun Context.openApprovedHomeSettings(page: String) {
  runCatching {
    startApprovedInternalActivity(approvedSettingsIntent(this, page))
  }
}

/** Return to Home's retained pages rather than creating another Apps or Tools host. */
internal fun Context.openApprovedHomeTab(tab: ApprovedLauncherTab, searchApps: Boolean = false): Boolean =
    try {
      PinnedAppScreensaver.navigation {
        ApprovedHomeNavigation.dispatch(approvedNavigationActivity(), tab, searchApps) {
          startApprovedInternalActivity(Intent(this, HomeActivity::class.java)
              .putExtra(APPROVED_HOME_TAB, tab.id)
              .putExtra(APPROVED_HOME_SEARCH, searchApps)
              .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
          true
        }
      }.optBoolean("ok")
    } catch (_: PinnedCommandPending) {
      // The entire navigation was admitted, including its launch. As with the existing Home
      // dispatcher, acknowledge admission rather than invite a second launch on a timeout.
      true
    } catch (_: Exception) { false }

/** Keep the existing Apps entry point for callers outside the launcher. */
internal fun Context.openApprovedHomeApps() {
  openApprovedHomeTab(ApprovedLauncherTab.APPS)
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
          context.openApprovedHomeTab(ApprovedLauncherTab.TOOLS)
        }
        // TimersOverlay shares its Tools button callback with Back. Register the Home
        // dialog's Back callback last so only the explicit button selects Tools.
        BackHandler(onBack = onDismiss)
      }
    }
  }
}
