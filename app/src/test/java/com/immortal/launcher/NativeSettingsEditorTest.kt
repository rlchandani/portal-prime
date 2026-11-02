/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.settings.BoolSpec
import com.immortal.launcher.settings.DerivedSpec
import com.immortal.launcher.settings.Entry
import com.immortal.launcher.settings.EnumSpec
import com.immortal.launcher.settings.IntSpec
import com.immortal.launcher.settings.NavSpec
import com.immortal.launcher.settings.SettingsDomain
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.settings.SettingsRegistry
import com.immortal.launcher.settings.StringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeSettingsEditorTest {
  private fun unread(): Nothing = error("Editor dispatch must not read or write settings")
  private fun text(secret: Boolean = false, entry: Entry = Entry.Inline) =
      StringSpec<Unit>("name", "Name", { unread() }, { _, _ -> unread() },
          secret = secret, entry = entry)
  private fun number(time: Boolean = false, asText: Boolean = false) =
      IntSpec<Unit>("value", "Value", { unread() }, { _, _ -> unread() },
          min = 0, max = 1439, timeOfDay = time, asText = asText)

  @Test fun togglesAndChoices_selectTheirNativeControlsWithoutReadingValues() {
    assertEquals(NativeSettingsEditor.TOGGLE, nativeSettingsEditor(
        BoolSpec<Unit>("on", "On", { unread() }, { _, _ -> unread() })))
    assertEquals(NativeSettingsEditor.CHOICE, nativeSettingsEditor(
        EnumSpec<Unit>("mode", "Mode", { unread() }, { _, _ -> unread() },
            options = listOf("first" to "First", "second" to "Second"))))
  }

  @Test fun numericFieldsAndSteppers_keepTheIntegerEditor() {
    assertEquals(NativeSettingsEditor.INTEGER, nativeSettingsEditor(number()))
    assertEquals(NativeSettingsEditor.INTEGER, nativeSettingsEditor(number(asText = true)))
  }

  @Test fun timeOfDay_usesClockEditorEvenWhenItsIntegerHintIsText() {
    assertEquals(NativeSettingsEditor.CLOCK_TIME, nativeSettingsEditor(number(time = true)))
    assertEquals(NativeSettingsEditor.CLOCK_TIME, nativeSettingsEditor(number(time = true, asText = true)))
  }

  @Test fun inlineStrings_selectTextEditorIncludingMaskedSecrets() {
    assertEquals(NativeSettingsEditor.TEXT, nativeSettingsEditor(text()))
    assertEquals(NativeSettingsEditor.TEXT, nativeSettingsEditor(text(secret = true)))
  }

  @Test fun activityBackedStrings_selectNavigationWithoutReadingOrLaunching() {
    assertEquals(NativeSettingsEditor.TEXT_NAVIGATION,
        nativeSettingsEditor(text(entry = Entry.Nav(EditorDestination::class.java))))
  }

  @Test fun richEditors_keepNavigationForImmediateAndWorkerLabels() {
    listOf(false, true).forEach { worker ->
      assertEquals(NativeSettingsEditor.NAVIGATION, nativeSettingsEditor(
          NavSpec<Unit>("editor", "Editor", { _, _ -> unread() },
              EditorDestination::class.java, valueOnWorker = worker)))
    }
  }

  @Test fun readonlyDerivedValues_cannotCreateAnEmptyEditableProjectionRow() {
    val derived = DerivedSpec<Unit>("legacy", { unread() }, render = true)
    assertEquals(NativeSettingsEditor.HIDDEN, nativeSettingsEditor(derived))
    val domain = SettingsDomain("test", "Test", { Unit }, listOf(text(), derived))
    val rows = prepareSettingsVisibility(domain, Unit, advanced = true) { true }
        .projection.items.filter { it.kind == ApprovedSettingsLazyKind.CONTROL }
    assertEquals(listOf("name"), rows.map { it.value!!.key })
    assertEquals(NativeSettingsEditor.TEXT, nativeSettingsEditor(rows.single().value!!))
  }

  @Test fun registeredWritableAndNavigationSpecs_cannotSelectHiddenEditor() {
    SettingsRegistry.domains.forEach { domain ->
      domain.specs.forEach { spec ->
        if (spec !is DerivedSpec<*>) {
          assertFalse("${domain.id}.${spec.key} selected the hidden editor",
              nativeSettingsEditor(spec) == NativeSettingsEditor.HIDDEN)
        }
      }
    }
  }

  @Test fun fleetAndDashboardText_controlsOmittedByTheOldCoverageListSelectTextEditor() {
    val name = SettingsDomains.fleet.specs.single { it.key == "name" }
    val url = SettingsDomains.homeDashboard.specs.single { it.key == "url" }
    assertEquals(NativeSettingsEditor.TEXT, nativeSettingsEditor(name))
    assertEquals(NativeSettingsEditor.TEXT, nativeSettingsEditor(url))
  }

  private class EditorDestination
}
