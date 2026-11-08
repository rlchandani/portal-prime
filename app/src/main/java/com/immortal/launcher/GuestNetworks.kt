/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.GuestWifi.Security

/**
 * Turns raw Wi-Fi scan results into the guest setup's network list: the connected network first
 * ("Connected now"), then by signal, one row per name, hidden and enterprise networks skipped.
 * Pure JVM; [GuestWifiScanner] feeds it from WifiManager.
 */
internal object GuestNetworks {
  /** One scan result as WifiManager reports it ([level] in dBm). */
  data class ScanEntry(val ssid: String, val capabilities: String, val level: Int)

  data class Nearby(val ssid: String, val security: Security, val level: Int, val connected: Boolean)

  private const val UNKNOWN_SSID = "<unknown ssid>"
  /** A connected network that didn't show up in the scan sorts as a strong one. */
  private const val CONNECTED_LEVEL = -40
  private val ENTERPRISE = listOf("EAP", "SUITE_B", "SUITE-B", "FT/EAP")

  /**
   * Security from a scan result's capabilities (`[RSN-PSK+SAE-CCMP][ESS]`); null for enterprise
   * networks, which need a username and can't be joined from a QR code. A WPA3 transition network
   * (PSK + SAE) reads as WPA so older phones can join; OWE ("Enhanced Open") needs no password.
   */
  fun security(capabilities: String): Security? {
    val caps = capabilities.uppercase()
    val psk = "PSK" in caps
    val sae = "SAE" in caps
    return when {
      !psk && !sae && ENTERPRISE.any { it in caps } -> null
      psk -> Security.WPA
      sae -> Security.SAE
      "WEP" in caps -> Security.WEP
      else -> Security.NONE
    }
  }

  /** WifiInfo's SSID without its quotes; null when unknown or blank. */
  fun cleanSsid(raw: String?): String? {
    val value = raw?.trim()?.removeSurrounding("\"") ?: return null
    return value.takeUnless { it.isBlank() || it == UNKNOWN_SSID || isHiddenName(it) }
  }

  fun nearby(results: List<ScanEntry>, connectedRaw: String?): List<Nearby> {
    val connected = cleanSsid(connectedRaw)
    val strongest = results.asSequence()
        .filter { !isHiddenName(it.ssid) }
        .mapNotNull { r -> security(r.capabilities)?.let { Nearby(r.ssid, it, r.level, r.ssid == connected) } }
        .groupBy { it.ssid }
        .map { (_, same) -> same.maxBy { it.level } }
    val withConnected = if (connected == null || strongest.any { it.connected }) strongest
        else strongest + Nearby(connected, Security.WPA, CONNECTED_LEVEL, connected = true)
    return withConnected.sortedWith(compareByDescending<Nearby> { it.connected }.thenByDescending { it.level }.thenBy { it.ssid })
  }

  /** 1–4 signal bars. */
  fun bars(level: Int): Int = when {
    level >= -55 -> 4
    level >= -65 -> 3
    level >= -75 -> 2
    else -> 1
  }

  private fun isHiddenName(ssid: String): Boolean = ssid.isBlank() || ssid.all { it == '\u0000' }
}
