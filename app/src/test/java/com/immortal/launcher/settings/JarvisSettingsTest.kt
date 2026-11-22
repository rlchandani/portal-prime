/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.jarvis.wake.JarvisWakeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Jarvis: "Hey Jarvis on Hearthpane screens" is registered, on by default, and has one home. */
class JarvisSettingsTest {
  @Suppress("UNCHECKED_CAST")
  private val spec = JarvisSettings.domain.specs.first { it.key == "wakeOnScreens" } as BoolSpec<JarvisWakeConfig.Settings>

  @Suppress("UNCHECKED_CAST")
  private val learn =
      JarvisSettings.domain.specs.first { it.key == "learnFromConversations" } as BoolSpec<JarvisWakeConfig.Settings>

  @Test fun learningFromConversationsDefaultsOffUntilTheOwnerTurnsItOn() {
    assertEquals(false, JarvisWakeConfig.Settings().learnFromConversations)
    assertEquals(false, learn.get(requireNotNull(JarvisSettings.domain.defaults)()))
    assertEquals("Jarvis learns from conversations", learn.title)
  }

  @Test fun whatJarvisKnowsIsANavRowToItsScreen() {
    val nav = JarvisSettings.domain.specs.first { it.key == JarvisSettings.KNOWS_KEY } as NavSpec<*>
    assertEquals(com.immortal.launcher.memory.JarvisKnowsActivity::class.java, nav.activity)
  }

  @Test fun wakeOnScreensDefaultsOn() {
    assertTrue(JarvisWakeConfig.Settings().wakeOnScreens)
    assertTrue(spec.get(requireNotNull(JarvisSettings.domain.defaults)()))
    assertEquals("Hey Jarvis on Hearthpane screens", spec.title)
  }

  @Test fun settingsCoverEveryPersistedField() {
    val fields = JarvisWakeConfig.Settings::class.java.declaredFields.map { it.name }.filterNot { it.contains('$') }.toSet()
    assertEquals(fields, JarvisSettings.domain.specs.map { it.key }.toSet() - JarvisSettings.KNOWS_KEY)
  }

  @Test fun registered() {
    assertTrue(SettingsDomains.all.any { it.id == JarvisSettings.domain.id })
  }

  @Test fun searchOpensSettingsJarvis() {
    assertEquals("jarvis", com.immortal.launcher.settingsControlHome("jarvis.wakeOnScreens"))
    assertEquals(com.immortal.launcher.SettingsGroup.CONNECTED_HOME, com.immortal.launcher.SettingsTree.page("jarvis")?.group)
  }
}
