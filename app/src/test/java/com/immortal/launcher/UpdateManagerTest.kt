/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Self-update manifest parsing + version-comparison logic (no device needed). */
class UpdateManagerTest {

  @Test
  fun parseManifest_readsAllFields() {
    val json =
        """{"versionCode":11,"versionName":"1.10","apkUrl":"https://x/immortal.apk","notes":"hi"}"""
    val info = UpdateManager.parseManifest(json)
    assertEquals(11L, info.versionCode)
    assertEquals("1.10", info.versionName)
    assertEquals("https://x/immortal.apk", info.apkUrl)
    assertEquals("hi", info.notes)
  }

  @Test
  fun parseManifest_optionalFieldsDefaultToBlank() {
    val info = UpdateManager.parseManifest("""{"versionCode":5,"apkUrl":"https://x/a.apk"}""")
    assertEquals(5L, info.versionCode)
    assertEquals("", info.versionName)
    assertEquals("", info.notes)
  }

  @Test
  fun shouldUpdate_onlyWhenStrictlyNewer() {
    assertTrue(UpdateManager.shouldUpdate(remoteVersionCode = 11, installedVersionCode = 10))
    assertFalse(UpdateManager.shouldUpdate(remoteVersionCode = 10, installedVersionCode = 10))
    assertFalse(UpdateManager.shouldUpdate(remoteVersionCode = 9, installedVersionCode = 10))
  }

  @Test
  fun cacheBust_appendsParamWhenNoQuery() {
    assertEquals("https://x/version.json?t=42", UpdateManager.cacheBust("https://x/version.json", 42))
  }

  @Test
  fun cacheBust_preservesExistingQuery() {
    assertEquals(
        "https://x/version.json?a=1&t=42",
        UpdateManager.cacheBust("https://x/version.json?a=1", 42),
    )
  }
}
