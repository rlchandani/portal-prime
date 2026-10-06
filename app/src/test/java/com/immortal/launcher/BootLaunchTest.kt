/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.pm.PackageManager
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.*

class BootLaunchTest {
  @Test fun legacyGoogleSearchSelections_areIgnoredWithoutRewritingOtherStartupChoices() {
    val folder = Files.createTempDirectory("immortal-boot-test").toFile()
    try {
      val context = mock(Context::class.java)
      `when`(context.getExternalFilesDir(null)).thenReturn(folder)
      val file = folder.resolve("boot_apps.txt")
      val original = "# Startup apps\ncom.android.quicksearchbox\norg.chromium.chrome\n" +
          "com.google.android.googlequicksearchbox\ncom.android.settings\n"
      file.writeText(original)
      assertEquals(listOf("org.chromium.chrome", "com.android.settings"), BootLaunch.packages(context))
      assertEquals(original, file.readText())
    } finally {
      folder.deleteRecursively()
    }
  }

  @Test fun bootDoesNotResolveRetiredGoogleSearch_butStillResolvesOtherStartupApps() {
    val folder = Files.createTempDirectory("immortal-boot-test").toFile()
    try {
      val context = mock(Context::class.java)
      val pm = mock(PackageManager::class.java)
      `when`(context.getExternalFilesDir(null)).thenReturn(folder)
      `when`(context.packageManager).thenReturn(pm)
      folder.resolve("boot_apps.txt").writeText(
          "com.android.quicksearchbox\ncom.google.android.googlequicksearchbox\norg.chromium.chrome\n")
      BootLaunch.launchAll(context)
      verify(pm, never()).getLaunchIntentForPackage("com.android.quicksearchbox")
      verify(pm, never()).getLaunchIntentForPackage("com.google.android.googlequicksearchbox")
      verify(pm).getLaunchIntentForPackage("org.chromium.chrome")
    } finally {
      folder.deleteRecursively()
    }
  }
}
