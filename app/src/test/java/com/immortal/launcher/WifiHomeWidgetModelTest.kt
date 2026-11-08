/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.YtSecureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Home Wi-Fi widget's pure model: setup state, sizes, password reveal and its timers. */
class WifiHomeWidgetModelTest {
  private fun settings(ssid: String = "Casa", security: GuestWifi.Security = GuestWifi.Security.WPA,
      hidden: Boolean = false, hidePassword: Boolean = true) =
      GuestConfig.Settings(ssid = ssid, security = security.wire, hiddenNetwork = hidden, hidePassword = hidePassword)

  @Test fun noSavedNetwork_isTheEmptyState() {
    assertSame(WifiWidgetState.NotSetUp, WifiWidgetState.from(GuestConfig.Settings(), password = ""))
    assertSame(WifiWidgetState.NotSetUp, WifiWidgetState.from(settings(ssid = "   "), password = "secret12"))
  }

  @Test fun aSecuredNetworkWithoutItsPassword_asksToFinishSetup() =
      assertSame(WifiWidgetState.NeedsPassword, WifiWidgetState.from(settings(), password = ""))

  @Test fun aConfiguredNetwork_isReadyWithTheGuestCardPayload() {
    val ready = WifiWidgetState.from(settings(), password = "secret12") as WifiWidgetState.Ready
    assertEquals("Casa", ready.ssid)
    assertEquals("WIFI:T:WPA;S:Casa;P:secret12;;", ready.payload)
    assertEquals(GuestWifi.payload(GuestWifi.Network("Casa", "secret12")), ready.payload)
    assertEquals("WPA / WPA2", ready.securityLabel)
    assertTrue(ready.hasPasswordRow)
  }

  @Test fun anOpenNetwork_usesNopass_andHasNoPasswordRow() {
    val ready = WifiWidgetState.from(settings(security = GuestWifi.Security.NONE), password = "") as WifiWidgetState.Ready
    assertEquals("WIFI:T:nopass;S:Casa;;", ready.payload)
    assertFalse(ready.hasPasswordRow)
    assertFalse(ready.payload.contains("P:"))
  }

  @Test fun anOpenNetwork_neverCarriesALeftoverPassword() {
    val ready = WifiWidgetState.from(settings(security = GuestWifi.Security.NONE), password = "old-secret") as WifiWidgetState.Ready
    assertFalse(ready.payload.contains("old-secret"))
    assertEquals("", ready.password)
  }

  @Test fun hiddenNetworksAndReservedCharactersUseTheSharedBuilder() {
    val ready = WifiWidgetState.from(settings(ssid = "My;Net", hidden = true), password = "a:b,c") as WifiWidgetState.Ready
    assertEquals("WIFI:T:WPA;S:My\\;Net;P:a\\:b\\,c;H:true;;", ready.payload)
  }

  @Test fun theQrDescription_namesTheNetwork_neverThePassword() {
    val ready = WifiWidgetState.from(settings(), password = "secret12") as WifiWidgetState.Ready
    assertEquals("Wi-Fi QR code for Casa. Scan with a phone camera to join.", ready.qrDescription)
    assertFalse(ready.qrDescription.contains("secret12"))
    assertFalse(ready.toString().contains("secret12"))
  }

  @Test fun oneSlot_isCompact_everyLargerSizeIsFull() {
    assertEquals(WifiWidgetLayout.COMPACT, WifiWidgetLayout.of(spanX = 1, spanY = 2))
    assertEquals(WifiWidgetLayout.COMPACT, WifiWidgetLayout.of(spanX = 1, spanY = 1))
    assertEquals(WifiWidgetLayout.FULL, WifiWidgetLayout.of(spanX = 2, spanY = 2))
    assertEquals(WifiWidgetLayout.FULL, WifiWidgetLayout.of(spanX = 1, spanY = 4))
    assertEquals(WifiWidgetLayout.FULL, WifiWidgetLayout.of(spanX = 2, spanY = 4))
  }

  @Test fun compact_showsOnlyTheQrAndName() {
    assertFalse(WifiWidgetLayout.COMPACT.showsDetails)
    assertTrue(WifiWidgetLayout.FULL.showsDetails)
  }

  @Test fun hiddenPassword_staysHiddenUntilTapped_thenFor30Seconds() {
    val hidden = WifiPasswordReveal()
    assertFalse(hidden.visible(nowMs = 0, hidePassword = true))
    val shown = hidden.reveal(nowMs = 1_000)
    assertEquals(31_000L, shown.untilMs)
    assertTrue(shown.visible(nowMs = 1_000, hidePassword = true))
    assertTrue(shown.visible(nowMs = 30_999, hidePassword = true))
    assertFalse(shown.visible(nowMs = 31_000, hidePassword = true))
    assertEquals(10_000L, shown.remainingMs(nowMs = 21_000))
    assertEquals(0L, shown.remainingMs(nowMs = 40_000))
    assertEquals(0L, hidden.remainingMs(nowMs = 5))
  }

  @Test fun revealingAgain_restartsTheTimer_andHideClearsIt() {
    val again = WifiPasswordReveal().reveal(0).reveal(20_000)
    assertEquals(50_000L, again.untilMs)
    assertFalse(again.hide().visible(nowMs = 20_001, hidePassword = true))
    assertEquals(WifiPasswordReveal.REVEAL_MS, 30_000L)
  }

  @Test fun whenTheGuestSettingDoesNotHideIt_thePasswordIsShown() =
      assertTrue(WifiPasswordReveal().visible(nowMs = 0, hidePassword = false))

  @Test fun theEnlargedQr_closesItselfAfterTwoMinutes() =
      assertEquals(120_000L, WifiQrOverlay.AUTO_CLOSE_MS)

  @Test fun theQrKeepsAFourModuleQuietZone() = assertTrue(WifiQrOverlay.QUIET_ZONE_MODULES >= 4)

  private class Fake(var text: String? = null) : YtSecureStore {
    override fun load(): String? = text
    override fun save(text: String) { this.text = text }
    override fun clear() { text = null }
  }

  @Test fun savingOrClearingThePassword_bumpsTheGuestCardRevision_soTheWidgetReloads() {
    val store = GuestPasswordStore(Fake())
    val start = GuestCardChanges.revision.value
    assertTrue(store.write("secret12"))
    val afterWrite = GuestCardChanges.revision.value
    assertNotEquals(start, afterWrite)
    assertTrue(store.clear())
    assertNotEquals(afterWrite, GuestCardChanges.revision.value)
  }

  @Test fun aRejectedPassword_doesNotBumpTheRevision() {
    val store = GuestPasswordStore(Fake())
    val start = GuestCardChanges.revision.value
    assertFalse(store.write("bad\npassword"))
    assertEquals(start, GuestCardChanges.revision.value)
  }
}
