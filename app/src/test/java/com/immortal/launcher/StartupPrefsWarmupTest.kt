/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/** The cold-start warm-up opens only real prefs files, in order, and never stops on a failure. */
class StartupPrefsWarmupTest {
  @Test fun everyNameIsStillAPrefsFileOfItsOwner() {
    val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
    val sources = root.walkTopDown().filter { it.extension == "kt" }
        .filter { it.name != "StartupPrefsWarmup.kt" }.map { it.readText() }.joinToString("\n")
    val missing = StartupPrefsWarmup.FILES.filterNot { name ->
      Regex("""(PREFS\s*=\s*"$name")|(getSharedPreferences\(\s*"$name")""").containsMatchIn(sources)
    }
    assertEquals("renamed or removed prefs files: $missing", emptyList<String>(), missing)
  }

  @Test fun namesAreUnique() {
    assertEquals(StartupPrefsWarmup.FILES.size, StartupPrefsWarmup.FILES.toSet().size)
  }

  @Test fun opensEachFileInOrderAndSkipsFailures() {
    val context = Mockito.mock(Context::class.java)
    val opened = mutableListOf<String>()
    Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.eq(Context.MODE_PRIVATE))).thenAnswer {
      val name = it.getArgument<String>(0)
      opened += name
      if (name == "b") throw IllegalStateException("locked")
      Mockito.mock(SharedPreferences::class.java)
    }
    assertEquals(listOf("b"), StartupPrefsWarmup.warm(context, listOf("a", "b", "c")))
    assertEquals(listOf("a", "b", "c"), opened)
  }

  @Test fun homesFirstFrameFilesAreWarmed() {
    // Cold-boot trace (1.285): unwarmed, these five cost ~47 ms of main-thread waits inside Home's first frame.
    val firstFrame = listOf("immortal_display", "immortal_share_fresh", "immortal_countdown", "immortal_todos", "immortal_crypto")
    assertEquals(emptyList<String>(), firstFrame.filterNot { it in StartupPrefsWarmup.FILES })
  }

  @Test fun theListStartsWithApplicationSetup() {
    assertTrue(StartupPrefsWarmup.FILES.indexOf("immortal_routines") < StartupPrefsWarmup.FILES.indexOf("immortal_welcome"))
  }
}
