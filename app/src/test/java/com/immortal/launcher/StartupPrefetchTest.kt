/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cold-start prefetch reads only the app's own dexopt output, code first. */
class StartupPrefetchTest {
  @Test fun mapsAbisToArtInstructionSetDirectories() {
    assertEquals("arm64", StartupPrefetch.isaFor("arm64-v8a"))
    assertEquals("arm", StartupPrefetch.isaFor("armeabi-v7a"))
    assertEquals("x86_64", StartupPrefetch.isaFor("x86_64"))
    assertNull(StartupPrefetch.isaFor("riscv64"))
  }

  @Test fun readsOdexThenVdexBesideTheApk() {
    val files = StartupPrefetch.artifacts("/data/app/pkg-1/base.apk", "arm64")
    assertEquals(listOf("/data/app/pkg-1/oat/arm64/base.odex", "/data/app/pkg-1/oat/arm64/base.vdex"),
        files.map { it.path })
  }

  @Test fun noParentDirectoryMeansNothingToRead() {
    assertTrue(StartupPrefetch.artifacts("base.apk", "arm64").isEmpty())
  }
}
