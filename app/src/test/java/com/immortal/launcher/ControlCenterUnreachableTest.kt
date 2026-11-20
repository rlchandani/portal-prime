/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mock-fidelity CC-3: the "Can't reach Home Assistant" checklist and the chrome it locks. */
class ControlCenterUnreachableTest {
  @Test fun theMocksThreeLines() {
    val lines = CcUnreachable.lines("https://homeassistant.example/", 7)
    assertEquals(listOf(CcCheckMark.OK, CcCheckMark.FAIL, CcCheckMark.WAIT), lines.map { it.mark })
    assertEquals("Portal Wi-Fi", lines[0].text)
    assertEquals("https://homeassistant.example — no response", lines[1].text)
    assertEquals("Retrying in 7 s (backs off to 60 s)", lines[2].text)
  }

  @Test fun retryingNowOnceTheCountdownEnds() {
    assertEquals("Retrying now", CcUnreachable.lines("http://ha.local:8123", 0).last().text)
  }

  @Test fun noSavedAddressReadsAsHomeAssistant() {
    assertEquals("Home Assistant", CcUnreachable.server("  "))
    assertEquals("Home Assistant — no response", CcUnreachable.lines("", 3)[1].text)
  }

  @Test fun onlyTheStateCardPhasesLockTheChrome() {
    val blocking = CcPhase.entries.filter { it.blocksHome }.toSet()
    assertEquals(setOf(CcPhase.UNREACHABLE, CcPhase.AUTH_FAILED, CcPhase.OFFLINE, CcPhase.NOT_CONFIGURED), blocking)
    assertFalse("a cold open never flashes a dimmed rail", CcPhase.CONNECTING.blocksHome)
    assertFalse(CcPhase.STALE.blocksHome)
    assertFalse(CcPhase.LIVE.blocksHome)
  }

  @Test fun theRailSliceCarriesIt() {
    assertTrue(CcRailSlice(CcScreen.MAP, 0, kids = false, hideCameras = false, hideSecurity = false, blocked = true).blocked)
    assertFalse(CcRailSlice(CcScreen.MAP, 0, kids = false, hideCameras = false, hideSecurity = false).blocked)
  }
}
