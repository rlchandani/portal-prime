/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import org.json.JSONObject

/** Authenticated diagnostic-build fixture; touches only this app's tagged test notification. */
internal object AppBadgeProbe {
  private const val channel = "immortal_badge_fixture_114"
  private const val tag = "immortal.native.badge.114"
  private const val id = 0x114BAD

  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (!BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) return response(403, "diagnostics_disabled")
    if (Build.VERSION.SDK_INT < 26) return response(409, "unsupported_platform")
    val manager = context.getSystemService(NotificationManager::class.java)
    if (req.method == "GET") return FleetHttpServer.Response(200,
        MediaNotificationListenerService.badgeDiagnostics(context.packageName)
            .put("fixtureChannelExists", manager.getNotificationChannel(channel) != null).toString())
    if (req.method != "POST") return response(405, "method_not_allowed")
    val action = runCatching { JSONObject(req.bodyText()).optString("action") }.getOrNull()
    when (action) {
      "post", "ongoing" -> {
        manager.createNotificationChannel(NotificationChannel(channel, "App badge verification", NotificationManager.IMPORTANCE_LOW))
        manager.notify(tag, id, Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Immortal verification")
            .setContentText("Temporary native badge fixture")
            .setOngoing(action == "ongoing").build())
      }
      "clear" -> { manager.cancel(tag, id); manager.deleteNotificationChannel(channel) }
      else -> return response(400, "unknown_action")
    }
    return FleetHttpServer.Response(200, JSONObject().put("ok", true).toString())
  }

  private fun response(status: Int, error: String) =
      FleetHttpServer.Response(status, JSONObject().put("ok", false).put("error", error).toString())
}
