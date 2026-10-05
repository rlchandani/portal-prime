/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class ApprovedHomeWallpaperSelectionTest {
  private class Preferences(vararg initial: Pair<String, Any>) {
    val values = initial.toMap().toMutableMap()
    private val pending = mutableMapOf<String, Any>()
    val context = mock(Context::class.java)
    val prefs = mock(SharedPreferences::class.java)
    val editor = mock(SharedPreferences.Editor::class.java)
    init {
      `when`(context.getSharedPreferences("immortal_wallpaper", Context.MODE_PRIVATE)).thenReturn(prefs)
      `when`(prefs.getString(anyString(), nullable(String::class.java))).thenAnswer {
        values[it.getArgument<String>(0)] as? String ?: it.getArgument<String?>(1)
      }
      `when`(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer {
        values[it.getArgument<String>(0)] as? Boolean ?: it.getArgument<Boolean>(1)
      }
      `when`(prefs.edit()).thenReturn(editor)
      `when`(editor.putString(anyString(), anyString())).thenAnswer {
        pending[it.getArgument<String>(0)] = it.getArgument<String>(1)
        editor
      }
      `when`(editor.putBoolean(anyString(), anyBoolean())).thenAnswer {
        pending[it.getArgument<String>(0)] = it.getArgument<Boolean>(1)
        editor
      }
      doAnswer { values.putAll(pending); pending.clear(); null }.`when`(editor).apply()
    }
  }

  @Test fun legacySkyDefault_usesQuietPaletteWithoutRewritingAnyStoredValue() {
    val f = Preferences("mode" to "sky", "grain" to true, "aurora_migrated" to false)
    val before = f.values.toMap()
    val loaded = WallpaperConfig.load(f.context)
    assertEquals(WallpaperConfig.DUSK_AURORA, loaded.mode)
    assertTrue(loaded.grain)
    assertEquals(before, f.values)
    verify(f.prefs, never()).edit()
  }

  @Test fun skySelectedAfterLegacyMigration_isProtectedWithoutNewSelectionMetadata() {
    val f = Preferences("mode" to "sky", "grain" to true, "aurora_migrated" to true)
    val before = f.values.toMap()
    assertEquals(WallpaperConfig.SKY, WallpaperConfig.load(f.context).mode)
    assertTrue(WallpaperConfig.load(f.context).grain)
    assertEquals(before, f.values)
    assertFalse(f.values.containsKey("mode_explicitly_selected"))
    verify(f.prefs, never()).edit()
  }

  @Test fun explicitlySelectingSky_survivesReloadAndOldMigrationMarker() {
    val f = Preferences("mode" to "sky", "grain" to true, "aurora_migrated" to false)
    WallpaperConfig.setMode(f.context, WallpaperConfig.SKY)
    assertEquals(WallpaperConfig.SKY, WallpaperConfig.load(f.context).mode)
    assertEquals(true, f.values["mode_explicitly_selected"])
    assertEquals(false, f.values["aurora_migrated"])
    assertTrue(WallpaperConfig.load(f.context).grain)
    verify(f.editor, times(1)).apply()
    // Both fields are part of one existing preferences edit; loading never edits preferences.
    verify(f.prefs, times(1)).edit()
  }

  @Test fun existingCustomWallpaperModes_areNeverTreatedAsLegacySkyDefaults() {
    listOf("dark", "starfield", "g_ocean", "g_space", "photo:02_denali.jpg", "screensaver").forEach { mode ->
      val f = Preferences("mode" to mode, "grain" to true)
      assertEquals(mode, WallpaperConfig.load(f.context).mode)
      assertEquals(mode, f.values["mode"])
      verify(f.prefs, never()).edit()
    }
  }
}
