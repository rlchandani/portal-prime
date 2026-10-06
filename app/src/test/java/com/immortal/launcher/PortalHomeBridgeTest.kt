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

class PortalHomeBridgeTest {
  @Test fun touchscreenPrefersMetaLauncher_evenWhenSiblingImmortalAndRipleyComeFirst() {
    val packages = listOf(
        "com.immortal.launcher",
        "com.immortal.launcher.debug",
        "com.facebook.aloha.system.ripleyhome",
        "com.facebook.alohaapps.launcher")

    assertEquals(
        "com.facebook.alohaapps.launcher",
        selectPortalHomePackage(packages, device = "dragon"))
  }

  @Test fun portalTvPrefersRipley_evenWhenTouchscreenLauncherComesFirst() {
    val packages = listOf(
        "com.facebook.alohaapps.launcher",
        "com.immortal.launcher.debug",
        "com.facebook.aloha.system.ripleyhome")

    assertEquals(
        "com.facebook.aloha.system.ripleyhome",
        selectPortalHomePackage(packages, device = "ripley"))
  }

  @Test fun onlyImmortalHomesNeverBecomeStockCandidates() {
    val packages = listOf("com.immortal.launcher", "com.immortal.launcher.debug")

    assertNull(selectPortalHomePackage(packages, device = "dragon"))
    assertNull(selectPortalHomePackage(packages, device = "ripley"))
  }

  @Test fun unknownAndSystemFallbackHomesNeverBecomeStockCandidates() {
    val packages = listOf("com.android.settings", "com.android.tv.settings", "other.launcher")

    assertNull(selectPortalHomePackage(packages, device = "dragon"))
    assertNull(selectPortalHomePackage(packages, device = "ripley"))
    assertNull(selectPortalHomePackage(emptyList(), device = "dragon"))
  }

  @Test fun missingPreferredHomeFallsBackOnlyToTheOtherKnownMetaHome() {
    assertEquals(
        "com.facebook.aloha.system.ripleyhome",
        selectPortalHomePackage(
            listOf("com.immortal.launcher.debug", "com.facebook.aloha.system.ripleyhome"),
            device = "dragon"))
    assertEquals(
        "com.facebook.alohaapps.launcher",
        selectPortalHomePackage(
            listOf("com.immortal.launcher", "com.facebook.alohaapps.launcher"),
            device = "ripley"))
  }
}
