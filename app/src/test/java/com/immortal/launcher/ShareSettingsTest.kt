/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareSettingsTest {
  @Test fun everyPersistedShareFieldHasASpec() {
    val fields = ShareConfig.Settings::class.java.declaredFields.filterNot { it.isSynthetic || it.name.startsWith("$") }
        .map { it.name }.toSet()
    assertEquals(fields, ShareSettings.domain.specs.map { it.key }.toSet())
  }

  @Test fun defaultsMatchThePrototypeRecommendation() {
    val json = ShareSettings.domain.flatJson(ShareConfig.Settings())
    assertEquals(true, json.getBoolean("allowSharing"))
    assertEquals(true, json.getBoolean("clearNotesOvernight"))
    assertEquals(true, json.getBoolean("freshPhotoBadge"))
    assertEquals(true, json.getBoolean("noteChime"))
  }
}
