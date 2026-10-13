/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class AppShortcutPreparationTest {
  private fun context() = mock(Context::class.java)

  private fun ordinaryApps(count: Int): List<AppEntry> = List(count) { index ->
    AppEntry("Installed app $index", mock(ComponentName::class.java), mock(ImageBitmap::class.java))
  }

  @Test fun largeInstalledLibraryDoesNotMultiplyShortcutPreparationAllocations() {
    val ctx = context()
    val apps = ordinaryApps(80)
    // Initialise Home's static Calls sentinel outside the measured constructor boundary.
    approvedShortcutIcons(ctx, emptyList())
    mockConstruction(ComponentName::class.java).use { constructors ->
      assertTrue(approvedShortcutIcons(ctx, apps).isEmpty())
      assertTrue("Shortcut targets must not be rebuilt for every installed app; created ${constructors.constructed().size}",
        constructors.constructed().size <= 4)
    }
  }

  @Test fun emptyLibraryDoesNotPrepareUnusedShortcutTargets() {
    val ctx = context()
    approvedShortcutIcons(ctx, emptyList())
    mockConstruction(ComponentName::class.java).use { constructors ->
      assertTrue(approvedShortcutIcons(ctx, emptyList()).isEmpty())
      assertEquals(0, constructors.constructed().size)
    }
  }

  @Test fun everyVisitUsesItsOwnContextRatherThanRetainingTheFirstActivity() {
    val first = context()
    val next = context()
    val apps = ordinaryApps(2)
    approvedShortcutIcons(first, emptyList())
    val contexts = mutableListOf<Any?>()
    mockConstruction(ComponentName::class.java) { _, construction ->
      contexts.add(construction.arguments()[0])
    }.use {
      approvedShortcutIcons(first, apps)
      approvedShortcutIcons(next, apps)
      assertEquals(8, contexts.size)
      assertTrue(contexts.take(4).all { it === first })
      assertTrue(contexts.drop(4).all { it === next })
    }
  }
}
