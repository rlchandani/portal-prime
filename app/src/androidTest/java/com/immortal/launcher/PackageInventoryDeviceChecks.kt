/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.io.File
import org.json.JSONObject

/** Owned-emulator HTTP fixture only; never emit generated pairing/fleet credentials. */
internal object PackageInventoryDeviceChecks {
  fun run(instrumentation: Instrumentation, cleanup: Boolean): Bundle {
    val context = instrumentation.targetContext
    check(context.packageName == "com.immortal.launcher.debug")
    check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) {
      "Inventory setup is restricted to a disposable emulator"
    }
    val assertions = JSONObject()
    fun requireCheck(label: String, ok: Boolean) {
      assertions.put(label, ok)
      check(ok) { label }
    }
    val privateSession = File(context.cacheDir, "inventory-probe-session.json")
    if (cleanup) {
      instrumentation.runOnMainSync {
        RemotePairing.revokeAll(context)
        RemotePairing.setEnabled(context, false)
        FleetConfig.setEnabled(context, false)
        context.stopService(Intent(context, FleetAgentService::class.java))
      }
      requireCheck("generated_session_removed", !privateSession.exists() || privateSession.delete())
      requireCheck("pairing_disabled", !RemotePairing.isEnabled(context))
      requireCheck("fleet_disabled", !FleetConfig.isEnabled(context))
    } else {
      requireCheck("receiver_registered", InstalledPackageVersions.register(context))
      val requested = setOf(context.packageName, "org.videolan.vlc",
          "com.immortal.inventory.nonexistent")
      val snapshot = checkNotNull(InstalledPackageVersions.snapshot(context, requested))
      requireCheck("requested_packages_match_uncached_android", requested.all { name ->
        val code = try {
          val pkg = context.packageManager.getPackageInfo(name, 0)
          if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode
              else @Suppress("DEPRECATION") pkg.versionCode.toLong()
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
        snapshot.versionCode(name) == code
      })
      requireCheck("warm_read_reuses_snapshot", snapshot === InstalledPackageVersions.snapshot(context, requested))
      requireCheck("unknown_package_is_absent", snapshot.versionCode("com.immortal.inventory.nonexistent") == null)
      var session: String? = null
      instrumentation.runOnMainSync {
        FleetConfig.setName(context, "Generated inventory emulator")
        FleetConfig.setEnabled(context, true)
        FleetAgentService.ensureRunning(context)
        RemotePairing.setEnabled(context, true)
        session = RemotePairing.redeem(context, RemotePairing.newPin())
      }
      requireCheck("generated_pairing_uses_normal_pin_exchange", session != null)
      // Private app cache is retrieved by the owned emulator host, never console output.
      privateSession.writeText(JSONObject().put("fleet", FleetConfig.token(context))
          .put("paired", session).put("port", FleetConfig.port(context)).toString())
      requireCheck("private_session_exists", privateSession.isFile)
    }
    // finish() force-stops the instrumentation target before queued apply() writes
    // necessarily reach disk. Empty commits flush normal setters without changing values.
    requireCheck("fleet_preferences_flushed", context.getSharedPreferences(
        "fleet_agent", Context.MODE_PRIVATE).edit().commit())
    requireCheck("pairing_preferences_flushed", context.getSharedPreferences(
        "remote_pairing", Context.MODE_PRIVATE).edit().commit())
    return Bundle().apply {
      putString("assertions", assertions.toString())
      putBoolean("packageInventoryCompleted", true)
      putBoolean("cleanup", cleanup)
      putString("scope", "Generated emulator package inventory and HTTP setup; no household apps or content")
    }
  }
}
