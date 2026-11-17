/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.BriefingConfig
import com.immortal.launcher.BriefingTrigger
import com.immortal.launcher.SettingsPageIds
import com.immortal.launcher.approvedSettingsSearch
import com.immortal.launcher.approvedSettingsSearchIndex
import com.immortal.launcher.settingsControlHome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Morning briefing is registered, lives in Alarms & Wake, and is findable by its old names. */
class BriefingSettingsTest {
  private val domain = BriefingSettings.domain

  @Test fun registered() {
    assertTrue(SettingsDomains.all.any { it.id == domain.id })
  }

  @Test fun settingsCoverEveryPersistedField() {
    val fields = BriefingConfig.Settings::class.java.declaredFields.map { it.name }.filterNot { it.contains('$') }.toSet()
    assertEquals(fields, domain.specs.map { it.key }.toSet())
  }

  @Test fun triggerOffersBothChoicesAndIgnoresUnknown() {
    val spec = domain.specs.single { it.key == "trigger" } as EnumSpec<BriefingConfig.Settings>
    assertEquals(listOf("alarm" to "Show at alarm time", "tap" to "Only when I tap Good morning"), spec.options)
    BriefingTrigger.entries.forEach { assertEquals(it.wire, spec.coerce(it.wire)) }
    assertNull(spec.coerce("always"))
  }

  @Test fun everyRowLivesInAlarmsAndWake() {
    listOf("enabled", "trigger", "speak").forEach {
      assertEquals(SettingsPageIds.ALARMS, settingsControlHome("briefing.$it"))
    }
  }

  @Test fun searchFindsTheBriefing() {
    val index = approvedSettingsSearchIndex()
    listOf("Morning briefing", "Good morning", "Read it aloud").forEach { query ->
      assertEquals(query, SettingsPageIds.ALARMS, approvedSettingsSearch(index, query).first().page)
    }
  }
}
