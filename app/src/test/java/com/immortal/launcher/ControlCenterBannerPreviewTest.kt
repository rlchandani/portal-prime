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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Mock-fidelity CC-7: "Preview a banner on Portal home". */
class ControlCenterBannerPreviewTest {
  @Test fun theMocksThreeChips() {
    assertEquals(listOf("Washer done", "Garage open", "Leak (critical)"), CcBannerPreview.samples.map { it.label })
    assertEquals(listOf(CcSeverity.INFO, CcSeverity.WARNING, CcSeverity.CRITICAL), CcBannerPreview.samples.map { it.alert.severity })
  }

  @Test fun samplesNeverPointAtAnEntityAndSayTheyArePreviews() {
    CcBannerPreview.samples.forEach { s ->
      assertTrue(s.label, CcBannerPreview.isPreview(s.alert))
      assertEquals("", s.alert.entityId)
      assertTrue(s.alert.body.startsWith("Preview"))
    }
  }

  @Test fun onlyTheCriticalTakesOverTheScreen() {
    assertEquals(listOf(false, false, true), CcBannerPreview.samples.map { CcBannerPreview.takeover(it.alert) })
  }

  @Test fun aRealAlertIsNeverTreatedAsAPreview() {
    val real = CcAlert("binary_sensor.leak:1", CcSeverity.CRITICAL, CcKind.LEAK, "binary_sensor.leak", "Leak", "", 1L, CcAlertAction.SHUT_WATER)
    assertFalse(CcBannerPreview.isPreview(real))
    assertFalse(CcBannerPreview.isPreview(real.copy(id = "preview:leak")))
  }

  private val real = CcAlert("binary_sensor.leak:1", CcSeverity.CRITICAL, CcKind.LEAK, "binary_sensor.leak", "Leak", "", 1L, CcAlertAction.SHUT_WATER)
  private val realWarn = CcAlert("cover.garage:1", CcSeverity.WARNING, CcKind.GARAGE, "cover.garage", "Garage", "", 1L, CcAlertAction.CLOSE)

  @Test fun everyChipMakesAShortSilentLocalPreview() {
    CcBannerPreview.samples.forEach { s ->
      val req = CcBannerPreview.request(s.alert)
      assertNotNull(s.label, req)
      req!!
      assertEquals(s.label, CcBannerPreview.TIMEOUT_MS, req.timeoutMs)
      assertTrue("a preview is short", req.timeoutMs <= 10_000L)
      assertFalse("a preview never sounds, quiet hours or not", req.sound)
      assertFalse("a preview never wakes the screen", req.wake)
      assertFalse("a preview is never told to other Portals", req.relay)
      assertEquals(CcBannerPreview.takeover(s.alert), req.takeover)
    }
  }

  @Test fun onlyTheSamplesThemselvesArePreviewed() {
    assertNull(CcBannerPreview.request(real))
    // Wearing a preview id isn't enough: a forged or edited "preview" is ignored too.
    assertNull(CcBannerPreview.request(CcBannerPreview.samples[0].alert.copy(title = "Front door unlocked")))
    assertNull(CcBannerPreview.request(real.copy(id = "preview:leak", entityId = "")))
  }

  @Test fun theCriticalSampleWouldSoundAtNightIfItWereReal() {
    // Why the preview path must bypass the policy: the real leak policy sounds even in quiet hours.
    val leak = CcBannerPreview.samples.last().alert
    assertTrue(CcBannerPolicy.decide(leak, ControlCenterConfig.Settings(), quietHours = true).sound)
    assertFalse(CcBannerPreview.request(leak)!!.sound)
  }

  @Test fun aPreviewNeverReplacesARealAlert() {
    assertTrue(CcBannerSlot.admitPreview(null))
    assertTrue("a preview replaces a preview", CcBannerSlot.admitPreview(CcBannerSlot.Showing(CcSeverity.CRITICAL, preview = true)))
    assertFalse(CcBannerSlot.admitPreview(CcBannerSlot.Showing(CcSeverity.INFO, preview = false)))
    assertFalse(CcBannerSlot.admitPreview(CcBannerSlot.Showing(CcSeverity.CRITICAL, preview = false)))
  }

  @Test fun aRealAlertAlwaysReplacesAPreviewEvenTheCriticalOne() {
    val leakPreview = CcBannerSlot.Showing(CcSeverity.CRITICAL, preview = true)
    assertTrue(CcBannerSlot.admitReal(leakPreview, realWarn))
    assertTrue(CcBannerSlot.admitReal(leakPreview, real))
    // Real alerts keep their own rule: a showing critical isn't covered by a warning.
    assertFalse(CcBannerSlot.admitReal(CcBannerSlot.Showing(CcSeverity.CRITICAL, preview = false), realWarn))
    assertTrue(CcBannerSlot.admitReal(CcBannerSlot.Showing(CcSeverity.WARNING, preview = false), real))
    assertTrue(CcBannerSlot.admitReal(null, realWarn))
  }

  @Test fun aPreviewIsDrawnInTheControlCentersOwnWindow() {
    // The accessibility host is unbound by any UiAutomation client (uiautomator) and by app updates;
    // the Control Center window isn't, so the preview prefers it even while the overlay is bound.
    assertEquals(CcBannerHost.ACTIVITY, CcBannerHost.forPreview(accessibility = true, activity = true))
    assertEquals(CcBannerHost.ACTIVITY, CcBannerHost.forPreview(accessibility = false, activity = true))
    assertEquals(CcBannerHost.ACCESSIBILITY, CcBannerHost.forPreview(accessibility = true, activity = false))
    assertEquals(CcBannerHost.NONE, CcBannerHost.forPreview(accessibility = false, activity = false))
    // A real alert never borrows an app window: it must show over any app, or not at all.
    assertEquals(CcBannerHost.NONE, CcBannerHost.forReal(accessibility = false))
    assertEquals(CcBannerHost.ACCESSIBILITY, CcBannerHost.forReal(accessibility = true))
  }

  @Test fun theAccessibilityHostGoingAwayOnlyTakesItsOwnOverlay() {
    assertTrue(CcBannerHost.removedOnDetach(CcBannerHost.ACCESSIBILITY))
    assertFalse("a preview in the Control Center window survives a rebind", CcBannerHost.removedOnDetach(CcBannerHost.ACTIVITY))
    assertFalse(CcBannerHost.removedOnDetach(CcBannerHost.NONE))
    val src = File("src/main/java/com/immortal/launcher/ControlCenterBanners.kt").readText()
    val detach = src.substringAfter("fun detach(").substringBefore("fun offer(")
    assertTrue(detach.contains("CcBannerHost.removedOnDetach(viewHost)"))
    // A real alert is parked, not dropped: the next bind draws it again (OverlayHoldTest).
    assertTrue(detach.contains("real.hostLost()"))
    assertFalse(detach.contains("dismissReal()"))
  }

  @Test fun thePreviewPathTouchesNoAlertSideEffect() {
    val src = File("src/main/java/com/immortal/launcher/ControlCenterBanners.kt").readText()
    val body = src.substringAfter("fun preview(").substringBefore("fun releaseActivity(")
    for (forbidden in listOf("HouseholdBus", "CcAlertRelay", "CcAlertGate", "CcAlertHistory", "ChimePlayer", "ScreenControl", "offer(",
        "CcBannerPolicy")) {
      assertFalse("preview() must not use $forbidden", body.contains(forbidden))
    }
    assertTrue(body.contains("CcBannerPreview.request("))
    assertTrue(body.contains("CcBannerSlot.admitPreview("))
  }

  @Test fun theChipsGoThroughThePreviewPathOnly() {
    val src = File("src/main/java/com/immortal/launcher/ControlCenterAlertsScreen.kt").readText()
    assertTrue(src.contains("ControlCenterBanners.preview(activity, sample.alert)"))
    assertFalse(src.contains("ControlCenterBanners.offer("))
  }
}
