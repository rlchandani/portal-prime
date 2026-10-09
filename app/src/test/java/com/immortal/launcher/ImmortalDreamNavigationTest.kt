/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class ImmortalDreamNavigationTest {
  @Test fun navigationFinishesAnOwnedDreamOnceAndMarksAnIntentionalExit() {
    val service = Mockito.mock(PhotoDreamService::class.java)
    val before = DreamPolicy.userExitAt
    try {
      DreamPolicy.userExitAt = 0L
      ImmortalDreamNavigation.attached(service)
      assertTrue(ImmortalDreamNavigation.dismissForNavigation())
      assertTrue(DreamPolicy.userExitAt > 0L)
      assertFalse(ImmortalDreamNavigation.dismissForNavigation())
      Mockito.verify(service, Mockito.times(1)).finish()
    } finally {
      ImmortalDreamNavigation.detached(service)
      DreamPolicy.userExitAt = before
    }
  }

  @Test fun navigationWithoutAnOwnedDreamDoesNotMarkAnExit() {
    val service = Mockito.mock(PhotoDreamService::class.java)
    val before = DreamPolicy.userExitAt
    try {
      ImmortalDreamNavigation.attached(service)
      ImmortalDreamNavigation.detached(service)
      assertFalse(ImmortalDreamNavigation.dismissForNavigation())
      assertEquals(before, DreamPolicy.userExitAt)
      Mockito.verify(service, Mockito.never()).finish()
    } finally { ImmortalDreamNavigation.detached(service) }
  }

  @Test fun oldLifecycleCallbackCannotDetachTheReplacementDream() {
    val old = Mockito.mock(PhotoDreamService::class.java)
    val replacement = Mockito.mock(PhotoDreamService::class.java)
    val before = DreamPolicy.userExitAt
    try {
      ImmortalDreamNavigation.attached(old)
      ImmortalDreamNavigation.attached(replacement)
      ImmortalDreamNavigation.detached(old)
      assertTrue(ImmortalDreamNavigation.dismissForNavigation())
      Mockito.verify(replacement).finish()
      Mockito.verify(old, Mockito.never()).finish()
    } finally {
      ImmortalDreamNavigation.detached(replacement)
      DreamPolicy.userExitAt = before
    }
  }
  @Test fun navigationFinishesAnOwnedPreviewEvenWhenHomeWasCold() {
    val preview = Mockito.mock(PhotoFramePreviewActivity::class.java)
    val before = DreamPolicy.userExitAt
    try {
      DreamPolicy.userExitAt = 0L
      ImmortalDreamNavigation.attached(preview)
      assertTrue(ImmortalDreamNavigation.dismissForNavigation())
      assertTrue(DreamPolicy.userExitAt > 0L)
      assertFalse(ImmortalDreamNavigation.dismissForNavigation())
      Mockito.verify(preview, Mockito.times(1)).finish()
    } finally {
      ImmortalDreamNavigation.detached(preview)
      DreamPolicy.userExitAt = before
    }
  }

  @Test fun oldPreviewDestructionCannotDetachItsReplacement() {
    val old = Mockito.mock(PhotoFramePreviewActivity::class.java)
    val next = Mockito.mock(PhotoFramePreviewActivity::class.java)
    val before = DreamPolicy.userExitAt
    try {
      ImmortalDreamNavigation.attached(old)
      ImmortalDreamNavigation.attached(next)
      ImmortalDreamNavigation.detached(old)
      assertTrue(ImmortalDreamNavigation.dismissForNavigation())
      Mockito.verify(next).finish()
      Mockito.verify(old, Mockito.never()).finish()
    } finally {
      ImmortalDreamNavigation.detached(next)
      DreamPolicy.userExitAt = before
    }
  }

  @Test fun deliberateNavigationStopsBothOwnedSurfacesBeforeAnyRecovery() {
    val preview = Mockito.mock(PhotoFramePreviewActivity::class.java)
    val dream = Mockito.mock(PhotoDreamService::class.java)
    val before = DreamPolicy.userExitAt
    try {
      ImmortalDreamNavigation.attached(preview)
      ImmortalDreamNavigation.attached(dream)
      assertTrue(ImmortalDreamNavigation.dismissForNavigation())
      Mockito.verify(dream).finish()
      Mockito.verify(preview).finish()
      assertFalse(ImmortalDreamNavigation.dismissForNavigation())
    } finally {
      ImmortalDreamNavigation.detached(preview)
      ImmortalDreamNavigation.detached(dream)
      DreamPolicy.userExitAt = before
    }
  }

}
