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

class StrictModeDiagnosticsTest {
  @Test fun onlyTheCompileTimeFlagInstallsIt() {
    assertTrue(StrictModeDiagnostics.shouldInstall(buildFlag = true))
    assertFalse(StrictModeDiagnostics.shouldInstall(buildFlag = false))
  }

  @Test fun ordinaryBuildsCompileItOff() {
    // Unit tests run against a normal debug build: the flag needs an explicit -PportalStrictMode.
    assertFalse(BuildConfig.PORTAL_STRICT_MODE_DIAGNOSTICS)
    assertFalse(StrictModeDiagnostics.shouldInstall())
  }
}
