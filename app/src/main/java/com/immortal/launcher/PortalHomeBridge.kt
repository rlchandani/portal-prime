/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast

private const val PORTAL_TOUCH_HOME_PACKAGE = "com.facebook.alohaapps.launcher"
private const val PORTAL_TV_HOME_PACKAGE = "com.facebook.aloha.system.ripleyhome"
private const val PORTAL_TV_DEVICE = "ripley"

/** Choose only Meta homes, regardless of which Immortal variants are installed or listed first. */
internal fun selectPortalHomePackage(packages: Iterable<String>, device: String): String? {
  val available = packages.toSet()
  val preferred = if (device == PORTAL_TV_DEVICE) {
    listOf(PORTAL_TV_HOME_PACKAGE, PORTAL_TOUCH_HOME_PACKAGE)
  } else {
    listOf(PORTAL_TOUCH_HOME_PACKAGE, PORTAL_TV_HOME_PACKAGE)
  }
  return preferred.firstOrNull { it in available }
}

/** Shared Calls handoff; the caller owns any bounded recovery from Meta's cold bootstrap redirect. */
object PortalHomeBridge {
  /** Begin a deliberate handoff, rolling back suppression when no launch can be dispatched. */
  fun launch(context: Context): Boolean {
    DreamPolicy.markBridge(context, portalHome = true)
    if (Build.DEVICE != PORTAL_TV_DEVICE) {
      // Suppress Meta's ambient bootstrap before opening Home, including a retained ambient task.
      if (!PortalHomeAmbientGuard.begin(context)) {
        DreamPolicy.clearBridge(context)
        Toast.makeText(context, "Calls couldn’t be opened. Please try again.", Toast.LENGTH_LONG).show()
        return false
      }
      runCatching {
        context.sendBroadcast(Intent("com.facebook.aloha.home.intent.action.DREAMING_STOP")
            .setPackage(PORTAL_TOUCH_HOME_PACKAGE))
      }
    }
    if (reopenAfterBootstrap(context)) return true

    // System Back can surface ripleyhome on Portal TV; it is not a touchscreen-home launch.
    if (Build.DEVICE == PORTAL_TV_DEVICE && RemoteInput.available()) {
      if (runCatching { RemoteInput.backRepeat(5, 300L) }.isSuccess) return true
    }

    val launched = runCatching {
      val homes = context.packageManager.queryIntentActivities(
          Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
          .map { it.activityInfo }
      val stockPackage = selectPortalHomePackage(homes.map { it.packageName }, Build.DEVICE)
          ?: return@runCatching false
      val stock = homes.first { it.packageName == stockPackage }
      context.startActivity(
          Intent(Intent.ACTION_MAIN)
              .addCategory(Intent.CATEGORY_LAUNCHER)
              .setComponent(ComponentName(stock.packageName, stock.name))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
      (context as? Activity)?.overridePendingTransition(0, 0)
      true
    }.getOrDefault(false)
    if (launched) return true

    DreamPolicy.clearBridge(context)
    Toast.makeText(context, "Portal calling is unavailable on this device.", Toast.LENGTH_LONG).show()
    return false
  }

  /**
   * Reopen interactive Meta Home after its bootstrap redirect. Preserve the caller's existing
   * bridge/token on both success and failure; this never begins a handoff or tries a fallback.
   */
  fun reopenAfterBootstrap(context: Context): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("portal://launcher/home"))
        .setPackage(PORTAL_TOUCH_HOME_PACKAGE)
    if (context.packageManager.resolveActivity(intent, 0) == null) return@runCatching false
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
      (context as? Activity)?.overridePendingTransition(0, 0)
    true
  }.getOrDefault(false)
}
