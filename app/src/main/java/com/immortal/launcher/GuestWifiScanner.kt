/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Reads nearby Wi-Fi networks for the guest setup. Android 10 returns scan results and the
 * connected network's name only with ACCESS_FINE_LOCATION granted and location turned on;
 * [status] says which of those is missing so the picker can explain it in one line.
 */
internal class GuestWifiScanner(context: Context) {
  enum class Status { READY, NEEDS_PERMISSION, LOCATION_OFF, WIFI_OFF }

  private val app = context.applicationContext
  private val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
  private val location = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

  fun hasPermission(): Boolean =
      ContextCompat.checkSelfPermission(app, PERMISSION) == PackageManager.PERMISSION_GRANTED

  fun status(): Status = when {
    !hasPermission() -> Status.NEEDS_PERMISSION
    location != null && !LocationManagerCompat.isLocationEnabled(location) -> Status.LOCATION_OFF
    wifi?.isWifiEnabled != true -> Status.WIFI_OFF
    else -> Status.READY
  }

  /** The current list; empty unless [status] is READY. Never throws. */
  @SuppressLint("MissingPermission") // guarded by status()
  @Suppress("DEPRECATION") // ScanResult.SSID / connectionInfo are the API 29 surface
  fun networks(): List<GuestNetworks.Nearby> {
    if (status() != Status.READY) return emptyList()
    return runCatching {
      val manager = wifi ?: return emptyList()
      val results = manager.scanResults.orEmpty().map { GuestNetworks.ScanEntry(it.SSID.orEmpty(), it.capabilities.orEmpty(), it.level) }
      GuestNetworks.nearby(results, manager.connectionInfo?.ssid)
    }.onFailure { Log.w(TAG, "scan read failed: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())
  }

  /** Asks for fresh results; Android throttles this, so the cached list is shown meanwhile. */
  @Suppress("DEPRECATION")
  fun scan(): Boolean = status() == Status.READY && runCatching { wifi?.startScan() == true }.getOrDefault(false)

  /** Calls [onResults] whenever new scan results land, until the returned function is called. */
  fun listen(onResults: () -> Unit): () -> Unit {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) = onResults()
    }
    ContextCompat.registerReceiver(app, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
        ContextCompat.RECEIVER_EXPORTED)
    return { runCatching { app.unregisterReceiver(receiver) } }
  }

  companion object {
    const val PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION
    private const val TAG = "ImmortalGuestWifi"

    /** After a denial: true when Android will no longer show the prompt ("Don't ask again"). */
    fun permanentlyDenied(context: Context): Boolean {
      val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? Activity }
      return activity != null && !activity.shouldShowRequestPermissionRationale(PERMISSION)
    }

    fun openAppSettings(context: Context) = launch(context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))

    fun openLocationSettings(context: Context) = launch(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    private fun launch(context: Context, intent: Intent) {
      runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
          .onFailure { Log.w(TAG, "settings screen unavailable: ${intent.action}") }
    }
  }
}
