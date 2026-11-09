/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.FollowMeConfig
import com.immortal.launcher.FollowMeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Music follows you" is registered, defaults to Ask first, and ignores unknown values. */
class FollowMeSettingsTest {
  private val spec = FollowMeSettings.domain.specs.single() as EnumSpec<FollowMeConfig.Settings>

  @Test fun defaultIsAskFirst() {
    assertEquals(FollowMeMode.ASK.wire, FollowMeConfig.Settings().mode)
    assertEquals(FollowMeMode.ASK.wire, spec.get(requireNotNull(FollowMeSettings.domain.defaults)()))
  }

  @Test fun everyModeIsAnOptionInOrder() {
    assertEquals(listOf("ask", "auto", "off"), spec.options.map { it.first })
    assertEquals(FollowMeMode.entries.map { it.wire }, spec.options.map { it.first })
  }

  @Test fun coerceAcceptsOnlyKnownModes() {
    FollowMeMode.entries.forEach { assertEquals(it.wire, spec.coerce(it.wire)) }
    assertNull(spec.coerce("always"))
  }

  @Test fun settingsCoverEveryPersistedField() {
    val fields = FollowMeConfig.Settings::class.java.declaredFields.map { it.name }.filterNot { it.contains('$') }.toSet()
    assertEquals(fields, FollowMeSettings.domain.specs.map { it.key }.toSet())
  }

  @Test fun registered() {
    assertTrue(SettingsDomains.all.any { it.id == FollowMeSettings.domain.id })
  }

  @Test fun searchOpensSettingsMusic() {
    assertEquals("music", com.immortal.launcher.settingsControlHome("${FollowMeSettings.domain.id}.mode"))
    assertEquals(com.immortal.launcher.SettingsGroup.CONNECTED_HOME, com.immortal.launcher.SettingsTree.page("music")?.group)
  }
}
