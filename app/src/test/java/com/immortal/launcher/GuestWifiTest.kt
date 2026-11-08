/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestWifiTest {
  @Test fun wpaPayloadUsesTheStandardShape() =
      assertEquals("WIFI:T:WPA;S:Home;P:secret123;;", GuestWifi.payload(GuestWifi.Network("Home", "secret123")))

  @Test fun reservedCharactersAreEscapedInNameAndPassword() =
      assertEquals(
          """WIFI:T:WPA;S:My\;Net\,\:\\;P:p\"a\;ss;;""",
          GuestWifi.payload(GuestWifi.Network("My;Net,:\\", "p\"a;ss")))

  @Test fun openNetworkUsesNopassAndOmitsThePassword() =
      assertEquals("WIFI:T:nopass;S:Cafe;;",
          GuestWifi.payload(GuestWifi.Network("Cafe", "ignored", GuestWifi.Security.NONE)))

  @Test fun wepAndHiddenAreEncoded() =
      assertEquals("WIFI:T:WEP;S:Old;P:abcde;H:true;;",
          GuestWifi.payload(GuestWifi.Network("Old", "abcde", GuestWifi.Security.WEP, hidden = true)))

  @Test fun blankNameHasNoPayload() = assertNull(GuestWifi.payload(GuestWifi.Network("  ", "x")))

  @Test fun securedNetworkWithoutPasswordHasNoPayload() =
      assertNull(GuestWifi.payload(GuestWifi.Network("Home", "")))

  @Test fun securityParsesWireNamesAndFallsBackToWpa() {
    assertEquals(GuestWifi.Security.WEP, GuestWifi.Security.fromWire("wep"))
    assertEquals(GuestWifi.Security.NONE, GuestWifi.Security.fromWire("none"))
    assertEquals(GuestWifi.Security.WPA, GuestWifi.Security.fromWire("bogus"))
  }

  @Test fun networkToStringNeverShowsThePassword() =
      assertEquals(false, GuestWifi.Network("Home", "hunter2").toString().contains("hunter2"))
}
