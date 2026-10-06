/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurationTest {
  @Test fun googleSearch_isExcludedRegardlessOfDeviceOrAppLabel() {
    for (pkg in listOf("com.android.quicksearchbox", "com.google.android.googlequicksearchbox")) {
      for (device in listOf("portal", "ripley", "unknown")) {
        for (label in listOf("Google Search", "Search", "")) {
          assertTrue("$pkg must not appear on $device", Curation.isHidden(pkg, label, device))
        }
      }
    }
  }

  @Test fun removingGoogleSearch_keepsBrowsersAndOtherGoogleAppsAvailable() {
    for (pkg in listOf("org.chromium.chrome", "org.mozilla.firefox", "com.google.android.gm")) {
      assertFalse(Curation.isHidden(pkg, "App", "portal"))
    }
  }
}
