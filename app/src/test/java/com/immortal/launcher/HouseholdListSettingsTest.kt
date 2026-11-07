/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.settings.HouseholdListSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class HouseholdListSettingsTest {
  /** Completeness tripwire: every persisted display option has a spec (and nothing else does). */
  @Test fun domainCoversEveryPersistedField() {
    val fields = HouseholdListConfig.Settings::class.java.declaredFields.map { it.name }.filter { !it.contains('$') }.toSet()
    assertEquals(fields, HouseholdListSettings.domain.specs.map { it.key }.toSet())
  }

  @Test fun defaultsMatchThePrototype() {
    val flat = HouseholdListSettings.domain.flatJson(HouseholdListConfig.Settings())
    assertEquals(true, flat.getBoolean("showWho"))
    assertEquals(false, flat.getBoolean("widgetShowsDone"))
  }
}
