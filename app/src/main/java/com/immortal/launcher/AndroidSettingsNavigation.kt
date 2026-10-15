/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Firmware-owned settings links have one guarded fallback and explicit recovery feedback. */
internal object AndroidSettingsNavigation {
  fun open(context: Context, title: String, action: String) {
    if (attempt { context.startActivity(Intent(action)) }) return
    // The general Settings row is already the fallback; do not dispatch it a second time.
    finishFallback(context, title, action != Settings.ACTION_SETTINGS)
  }

  fun openPortal(context: Context) {
    val preferred = try {
      context.packageManager.getLaunchIntentForPackage("com.facebook.alohaapps.settings")
    } catch (_: Exception) { null }
    if (preferred != null && attempt { context.startActivity(preferred) }) return
    finishFallback(context, "Portal Settings", true)
  }

  private fun finishFallback(context: Context, title: String, allowFallback: Boolean) {
    val opened = allowFallback && attempt {
      context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
    val message = if (opened) "$title isn’t available here. Opening Android settings instead."
        else "$title couldn’t be opened. Please try again."
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
  }

  private fun attempt(launch: () -> Unit): Boolean = try {
    launch()
    true
  } catch (_: Exception) { false }
}
