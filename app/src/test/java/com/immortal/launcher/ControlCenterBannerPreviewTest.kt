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
}
