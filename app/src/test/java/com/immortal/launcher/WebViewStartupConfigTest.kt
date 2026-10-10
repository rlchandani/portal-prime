/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class WebViewStartupConfigTest {
  @Test fun `process snapshot is readable before Home without Android runtime calls`() {
    assertEquals(WebViewStartupState(), WebViewStartupPreparation.snapshot())
  }

  @Test fun `actual AndroidX configuration disables UI startup without running the executor`() {
    val executor = Executor { throw AssertionError("Config construction submitted provider work") }
    val config = backgroundOnlyWebViewConfig(executor)
    assertSame(executor, config.backgroundExecutor)
    assertFalse("AndroidX would run the UI startup portion", config.shouldRunUiThreadStartUpTasks())
    assertNull(config.profilesToLoadDuringStartup)
  }
}
