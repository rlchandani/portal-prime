/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The fresh-photo badge must show even on faces where the household turned photo captions off. */
class CaptionLinesTest {
  @Test fun captionsOffStillShowTheFreshPhotoBadgeOnly() {
    val lines = CaptionLines.resolve(metadataOn = false, place = "Edmonds", date = "Nov 7", memory = "2 years ago today",
        fresh = "New from Pixel · just now")
    assertEquals(CaptionLines.Lines("New from Pixel · just now", null, null), lines)
  }

  @Test fun captionsOffWithoutAFreshPhotoShowNothing() =
      assertNull(CaptionLines.resolve(false, "Edmonds", "Nov 7", "2 years ago today", null))

  @Test fun captionsOnShowPlaceDateAndTheFreshBadgeOverTheMemory() =
      assertEquals(CaptionLines.Lines("New from Pixel · just now", "Edmonds", "Nov 7"),
          CaptionLines.resolve(true, "Edmonds", "Nov 7", "2 years ago today", "New from Pixel · just now"))

  @Test fun captionsOnKeepTheMemoryBadgeWhenNothingIsFresh() =
      assertEquals(CaptionLines.Lines("2 years ago today", "Edmonds", null),
          CaptionLines.resolve(true, "Edmonds", " ", "2 years ago today", null))

  @Test fun blankEverythingHidesTheCaption() = assertNull(CaptionLines.resolve(true, "", null, " ", null))
}
