/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/** Real dismissal/recovery policy; Android surfaces are boundary doubles. */
class DreamTapContinuationTest {
  @Test fun unpinnedTapRetiresContinuationBeforeConfiguredLaunchAndDreamFinish() = withSurfaces { dream, preview, context ->
    val calls = mutableListOf<String>()
    Mockito.doAnswer {
      assertTrue(DreamPolicy.userExitAt > 0L)
      calls += "preview"
      DreamPolicy.onImmortalDreamingStopped(context)
      null
    }.`when`(preview).finish()
    exitPinnedDream(false, false, { fail("Unpinned"); false }, { fail("No lost pin") },
        { calls += "launch" }, { calls += "dream" },
        retireContinuation = { ImmortalDreamNavigation.dismissContinuationForTap(dream) })
    assertEquals(listOf("preview", "launch", "dream"), calls)
    Mockito.verify(context, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
    Mockito.verify(preview, Mockito.times(1)).finish()
    assertFalse(ImmortalDreamNavigation.dismissContinuationForTap(dream))
  }

  @Test fun tapLeavesDeliberatePhotoViewerUnderTheDream() = withSurfaces(continuation = false) { dream, preview, _ ->
    assertFalse(ImmortalDreamNavigation.dismissContinuationForTap(dream))
    Mockito.verify(preview, Mockito.never()).finish()
    assertTrue(ImmortalDreamNavigation.dismissForNavigation())
    Mockito.verify(preview).finish()
  }

  @Test fun staleDreamCannotRetireTheReplacementDreamsContinuation() = withSurfaces { dream, preview, _ ->
    val stale = Mockito.mock(PhotoDreamService::class.java)
    assertFalse(ImmortalDreamNavigation.dismissContinuationForTap(stale))
    assertEquals(0L, DreamPolicy.userExitAt)
    Mockito.verify(preview, Mockito.never()).finish()
    assertTrue(ImmortalDreamNavigation.dismissContinuationForTap(dream))
    Mockito.verify(preview).finish()
  }

  @Test fun lateOldPreviewDetachCannotClearANewContinuation() = withSurfaces { dream, preview, _ ->
    assertTrue(ImmortalDreamNavigation.dismissContinuationForTap(dream))
    val replacement = Mockito.mock(PhotoFramePreviewActivity::class.java)
    `when`(replacement.isScreensaverContinuation).thenReturn(true)
    try {
      ImmortalDreamNavigation.attached(replacement)
      ImmortalDreamNavigation.detached(preview)
      assertTrue(ImmortalDreamNavigation.dismissContinuationForTap(dream))
      Mockito.verify(replacement, Mockito.times(1)).finish()
    } finally { ImmortalDreamNavigation.detached(replacement) }
  }

  @Test fun admittedPinnedReturnNeverRetiresAnUnderlyingActivity() = withSurfaces { dream, preview, _ ->
    var finished = 0
    exitPinnedDream(true, true, { true }, { fail("No lost pin") }, { fail("No app launch") },
        { finished++ }, retireContinuation = { ImmortalDreamNavigation.dismissContinuationForTap(dream) })
    assertEquals(1, finished)
    Mockito.verify(preview, Mockito.never()).finish()
    assertEquals(0L, DreamPolicy.userExitAt)
  }

  @Test fun lostPinnedReturnKeepsBothSurfacesUntilTheBarrierIsReady() = withSurfaces { dream, preview, _ ->
    var lost = 0
    exitPinnedDream(true, false, { false }, { lost++ }, { fail("No app launch") },
        { fail("Keep dream") }, retireContinuation = { ImmortalDreamNavigation.dismissContinuationForTap(dream) })
    assertEquals(1, lost)
    Mockito.verify(preview, Mockito.never()).finish()
  }

  private fun withSurfaces(continuation: Boolean = true,
      action: (PhotoDreamService, PhotoFramePreviewActivity, Context) -> Unit) {
    val context = ScreensaverTestPrefs(mapOf("enabled" to true)).context
    val power = Mockito.mock(PowerManager::class.java)
    `when`(power.isInteractive).thenReturn(true)
    `when`(context.getSystemService(PowerManager::class.java)).thenReturn(power)
    `when`(context.applicationContext).thenReturn(context)
    val dream = Mockito.mock(PhotoDreamService::class.java)
    val preview = Mockito.mock(PhotoFramePreviewActivity::class.java)
    `when`(preview.isScreensaverContinuation).thenReturn(continuation)
    val markers = Triple(DreamPolicy.userExitAt, DreamPolicy.bridgeAt, DreamPolicy.inStockHandoff)
    try {
      DreamPolicy.userExitAt = 0L
      DreamPolicy.bridgeAt = 0L
      DreamPolicy.inStockHandoff = false
      ImmortalDreamNavigation.attached(dream)
      ImmortalDreamNavigation.attached(preview)
      Mockito.mockStatic(Log::class.java).use {
        Mockito.mockConstruction(Intent::class.java,
            Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)).use {
          action(dream, preview, context)
        }
      }
    } finally {
      ImmortalDreamNavigation.detached(dream)
      ImmortalDreamNavigation.detached(preview)
      DreamPolicy.userExitAt = markers.first
      DreamPolicy.bridgeAt = markers.second
      DreamPolicy.inStockHandoff = markers.third
    }
  }
}
