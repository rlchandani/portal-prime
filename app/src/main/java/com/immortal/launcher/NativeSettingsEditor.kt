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
import com.immortal.launcher.settings.SettingSpec
import com.immortal.launcher.settings.StringSpec

/** Native editor dispatch; presentation eligibility remains a separate concern. */
internal enum class NativeSettingsEditor {
  TOGGLE, CHOICE, INTEGER, CLOCK_TIME, TEXT, TEXT_NAVIGATION, NAVIGATION, HIDDEN,
}

internal fun nativeSettingsEditor(spec: SettingSpec<*>): NativeSettingsEditor =
    when (spec) {
      is BoolSpec<*> -> NativeSettingsEditor.TOGGLE
      is EnumSpec<*> -> NativeSettingsEditor.CHOICE
      is IntSpec<*> -> if (spec.timeOfDay) NativeSettingsEditor.CLOCK_TIME else NativeSettingsEditor.INTEGER
      is StringSpec<*> -> when (spec.entry) {
        Entry.Inline -> NativeSettingsEditor.TEXT
        is Entry.Nav -> NativeSettingsEditor.TEXT_NAVIGATION
      }
      is NavSpec<*> -> NativeSettingsEditor.NAVIGATION
      is DerivedSpec<*> -> NativeSettingsEditor.HIDDEN
    }
