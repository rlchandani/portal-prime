/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.GuestWifi.Security
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestNetworksTest {
  private fun scan(ssid: String, caps: String, level: Int = -60) = GuestNetworks.ScanEntry(ssid, caps, level)

  @Test fun securityFromCapabilities() {
    assertEquals(Security.WPA, GuestNetworks.security("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
    assertEquals(Security.WPA, GuestNetworks.security("[WPA-PSK-TKIP+CCMP][ESS]"))
    assertEquals(Security.SAE, GuestNetworks.security("[RSN-SAE-CCMP][ESS][MFPR]"))
    assertEquals("WPA3 transition mode joins as WPA2", Security.WPA, GuestNetworks.security("[RSN-PSK+SAE-CCMP][ESS]"))
    assertEquals(Security.WEP, GuestNetworks.security("[WEP][ESS]"))
    assertEquals(Security.NONE, GuestNetworks.security("[ESS]"))
    assertEquals(Security.NONE, GuestNetworks.security(""))
    assertEquals("Enhanced Open needs no password", Security.NONE, GuestNetworks.security("[RSN-OWE-CCMP][ESS][MFPR]"))
    assertEquals(Security.NONE, GuestNetworks.security("[ESS][OWE_TRANSITION]"))
  }

  @Test fun enterpriseNetworksCannotBeSharedAndAreSkipped() {
    assertNull(GuestNetworks.security("[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS]"))
    assertNull(GuestNetworks.security("[RSN-SUITE_B_192-GCMP-256][ESS]"))
    assertTrue(GuestNetworks.nearby(listOf(scan("Office", "[RSN-EAP/SHA1-CCMP][ESS]")), null).isEmpty())
  }

  @Test fun dedupesBySsidKeepingTheStrongest() {
    val list = GuestNetworks.nearby(listOf(
        scan("Home", "[WPA2-PSK-CCMP]", -80), scan("Home", "[RSN-SAE-CCMP]", -40), scan("Cafe", "[ESS]", -70)), null)
    assertEquals(listOf("Home", "Cafe"), list.map { it.ssid })
    assertEquals(Security.SAE, list[0].security)
    assertEquals(-40, list[0].level)
  }

  @Test fun sortsBySignalThenName() {
    val list = GuestNetworks.nearby(listOf(scan("B", "", -70), scan("A", "", -70), scan("Strong", "", -30)), null)
    assertEquals(listOf("Strong", "A", "B"), list.map { it.ssid })
  }

  @Test fun connectedNetworkComesFirstAndIsLabelled() {
    val list = GuestNetworks.nearby(listOf(scan("Neighbour", "[WPA2-PSK-CCMP]", -30), scan("Home", "[WPA2-PSK-CCMP]", -75)), "\"Home\"")
    assertEquals(listOf("Home", "Neighbour"), list.map { it.ssid })
    assertTrue(list[0].connected)
    assertFalse(list[1].connected)
  }

  @Test fun connectedNetworkMissingFromScanIsStillOffered() {
    val list = GuestNetworks.nearby(listOf(scan("Other", "", -50)), "Hidden Home")
    assertEquals("Hidden Home", list[0].ssid)
    assertTrue(list[0].connected)
    assertEquals(Security.WPA, list[0].security)
  }

  @Test fun hiddenAndUnknownSsidsAreSkipped() {
    val list = GuestNetworks.nearby(listOf(scan("", "[WPA2-PSK-CCMP]"), scan("\u0000\u0000", ""), scan("Real", "")), "<unknown ssid>")
    assertEquals(listOf("Real"), list.map { it.ssid })
  }

  @Test fun connectedSsidIsUnquoted() {
    assertEquals("Home", GuestNetworks.cleanSsid("\"Home\""))
    assertNull(GuestNetworks.cleanSsid("<unknown ssid>"))
    assertNull(GuestNetworks.cleanSsid(null))
    assertNull(GuestNetworks.cleanSsid("\"\""))
  }

  @Test fun signalBars() {
    assertEquals(4, GuestNetworks.bars(-45))
    assertEquals(3, GuestNetworks.bars(-60))
    assertEquals(2, GuestNetworks.bars(-70))
    assertEquals(1, GuestNetworks.bars(-85))
  }
}
