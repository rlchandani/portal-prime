/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
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

  @Test fun readPackages_storageUnavailable_throwsInsteadOfReportingNoSelections() {
    val context = mock(Context::class.java)
    `when`(context.getExternalFilesDir(null)).thenReturn(null)

    assertThrows(Exception::class.java) { BootLaunch.readPackages(context) }
  }

  @Test fun readPackages_destinationIsDirectory_throwsInsteadOfReportingNoSelections() {
    withExternalFolder { context, folder ->
      assertTrue(folder.resolve("boot_apps.txt").mkdir())

      assertThrows(Exception::class.java) { BootLaunch.readPackages(context) }
    }
  }

  @Test fun readPackages_missingFileInValidStorage_returnsEmptyWithoutCreatingFiles() {
    withExternalFolder { context, folder ->
      assertEquals(emptyList<String>(), BootLaunch.readPackages(context))
      assertEquals(emptyList<String>(), folder.listFiles()!!.map { it.name })
    }
  }

  @Test fun readPackages_filtersCommentsWhitespaceAndRetiredPackages_withoutRewritingFile() {
    withExternalFolder { context, folder ->
      val file = folder.resolve("boot_apps.txt")
      val original = "  # Startup apps\n\n  org.chromium.chrome  \n" +
          "com.android.quicksearchbox\ncom.google.android.googlequicksearchbox\n" +
          "  com.android.settings\n  \n"
      file.writeText(original)

      assertEquals(listOf("org.chromium.chrome", "com.android.settings"),
          BootLaunch.readPackages(context))
      assertEquals(original, file.readText())
    }
  }

  @Test fun packages_storageUnavailable_keepsBootReceiverSafeEmptyFallback() {
    val context = mock(Context::class.java)
    `when`(context.getExternalFilesDir(null)).thenReturn(null)

    assertEquals(emptyList<String>(), BootLaunch.packages(context))
  }

  @Test fun packages_destinationIsDirectory_keepsBootReceiverSafeEmptyFallback() {
    withExternalFolder { context, folder ->
      assertTrue(folder.resolve("boot_apps.txt").mkdir())

      assertEquals(emptyList<String>(), BootLaunch.packages(context))
    }
  }

  @Test fun setPackages_storageUnavailable_returnsFalseAndKeepsExistingChoices() {
    withExternalFolder { context, folder ->
      val file = folder.resolve("boot_apps.txt")
      val original = "# Existing choices\norg.chromium.chrome\n"
      file.writeText(original)
      `when`(context.getExternalFilesDir(null)).thenReturn(null)

      assertFalse(BootLaunch.setPackages(context, listOf("com.android.settings")))
      assertEquals(original, file.readText())
      assertEquals(listOf("boot_apps.txt"), folder.listFiles()!!.map { it.name })
    }
  }

  @Test fun setPackages_destinationIsDirectory_returnsFalseAndCleansOnlyOwnedTempFile() {
    withExternalFolder { context, folder ->
      val destination = folder.resolve("boot_apps.txt")
      assertTrue(destination.mkdir())
      val child = destination.resolve("keep.txt")
      child.writeText("keep destination contents")
      val unrelatedTemp = folder.resolve("unrelated.tmp")
      unrelatedTemp.writeText("keep unrelated temporary file")

      assertFalse(BootLaunch.setPackages(context, listOf("com.android.settings")))
      assertTrue(destination.isDirectory)
      assertEquals("keep destination contents", child.readText())
      assertEquals("keep unrelated temporary file", unrelatedTemp.readText())
      assertEquals(setOf("boot_apps.txt", "unrelated.tmp"),
          folder.listFiles()!!.map { it.name }.toSet())
    }
  }

  @Test fun setPackages_overwritesByReplacingFile_andReadsBackWithoutTempFiles() {
    withExternalFolder { context, folder ->
      val file = folder.resolve("boot_apps.txt")
      val original = "# Existing choices\norg.chromium.chrome\n"
      file.writeText(original)

      // An in-place write changes this open reader's contents; replacing the file does not.
      file.bufferedReader().use { previousFile ->
        assertTrue(BootLaunch.setPackages(context,
            listOf("com.android.settings", "org.chromium.chrome")))
        assertEquals(listOf("com.android.settings", "org.chromium.chrome"),
            BootLaunch.readPackages(context))
        assertEquals(listOf("com.android.settings", "org.chromium.chrome"),
            BootLaunch.packages(context))
        assertEquals(original, previousFile.readText())
      }
      assertEquals(listOf("boot_apps.txt"), folder.listFiles()!!.map { it.name })
    }
  }

  @Test fun setPackages_createsMissingFile_andReadsBackWithoutTempFiles() {
    withExternalFolder { context, folder ->
      assertTrue(BootLaunch.setPackages(context, listOf("org.chromium.chrome")))

      assertEquals(listOf("org.chromium.chrome"), BootLaunch.readPackages(context))
      assertEquals(listOf("boot_apps.txt"), folder.listFiles()!!.map { it.name })
    }
  }

  @Test fun setPackages_unwritableFolder_preservesExistingChoicesAndLeavesNoTempFile() {
    withExternalFolder { context, folder ->
      val file = folder.resolve("boot_apps.txt")
      val original = "# Existing choices\norg.chromium.chrome\n"
      file.writeText(original)
      // Keep the destination writable: an unsafe direct write could still truncate it.
      assertTrue(file.canWrite())
      try {
        assertTrue(folder.setWritable(false, false))
        assumeFalse("Host bypasses directory write permissions", folder.canWrite())

        assertFalse(BootLaunch.setPackages(context, listOf("com.android.settings")))
        assertEquals(original, file.readText())
        assertEquals(listOf("org.chromium.chrome"), BootLaunch.readPackages(context))
        assertEquals(listOf("boot_apps.txt"), folder.listFiles()!!.map { it.name })
      } finally {
        assertTrue(folder.setWritable(true, true))
      }
    }
  }

  @Test fun readPackages_folderWithoutSearchPermission_doesNotInventEmptySelections() {
    withExternalFolder { context, folder ->
      val file = folder.resolve("boot_apps.txt")
      val original = "org.chromium.chrome\n"
      file.writeText(original)
      try {
        assertTrue(folder.setExecutable(false, false))
        assumeFalse("Host bypasses directory search permissions", folder.canExecute())
        assertThrows(java.io.IOException::class.java) { BootLaunch.readPackages(context) }
      } finally {
        assertTrue(folder.setExecutable(true, true))
      }
      assertEquals(original, file.readText())
    }
  }

  private fun withExternalFolder(test: (Context, File) -> Unit) {
    val folder = Files.createTempDirectory("immortal-boot-test").toFile()
    try {
      val context = mock(Context::class.java)
      `when`(context.getExternalFilesDir(null)).thenReturn(folder)
      test(context, folder)
    } finally {
      folder.deleteRecursively()
    }
  }
}
