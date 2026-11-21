/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Music stage wording and the repeat cycle that its button walks. */
class MusicV3StageCopyCoverageTest {
  @Test fun peekLabelDependsOnWhetherAnythingIsLoaded() {
    assertEquals("Library", MusicV3StageCopy.peekLabel(idle = true))
    assertEquals("Up next", MusicV3StageCopy.peekLabel(idle = false))
  }

  @Test fun playingAndMovedSlotNameTheirSubject() {
    assertEquals("Playing Road trip", MusicV3StageCopy.playing("Road trip"))
    assertEquals("Playing in Kitchen", MusicV3StageCopy.movedSlot("Kitchen"))
  }

  @Test fun quietNoteOmitsTheTimeWhenThereIsNone() {
    assertEquals("Quiet hours · volume stays low until 7:00 am", MusicV3StageCopy.quietNote("7:00 am"))
    assertEquals("Quiet hours · volume stays low", MusicV3StageCopy.quietNote(null))
    assertEquals("Quiet hours · volume stays low", MusicV3StageCopy.quietNote("   "))
  }

  @Test fun repeatCyclesOffAllOneAndBack() {
    assertEquals(YtRepeat.ALL, MusicV3StageCopy.nextRepeat(YtRepeat.OFF))
    assertEquals(YtRepeat.ONE, MusicV3StageCopy.nextRepeat(YtRepeat.ALL))
    assertEquals(YtRepeat.OFF, MusicV3StageCopy.nextRepeat(YtRepeat.ONE))
    YtRepeat.values().forEach { start ->
      var r = start
      repeat(3) { r = MusicV3StageCopy.nextRepeat(r) }
      assertEquals("three taps return to $start", start, r)
    }
  }

  @Test fun everyRepeatModeHasItsOwnLabelAndToast() {
    assertEquals("Repeat", MusicV3StageCopy.repeatLabel(YtRepeat.OFF))
    assertEquals("Repeat, all songs", MusicV3StageCopy.repeatLabel(YtRepeat.ALL))
    assertEquals("Repeat, this song", MusicV3StageCopy.repeatLabel(YtRepeat.ONE))
    assertEquals("Repeat off", MusicV3StageCopy.repeatToast(YtRepeat.OFF))
    assertEquals("Repeating the queue", MusicV3StageCopy.repeatToast(YtRepeat.ALL))
    assertEquals("Repeating this song", MusicV3StageCopy.repeatToast(YtRepeat.ONE))
    assertEquals(YtRepeat.values().size, YtRepeat.values().map(MusicV3StageCopy::repeatLabel).toSet().size)
  }

  @Test fun shortToastIsShorterThanTheLongOne() {
    assertNotEquals(MusicV3StageCopy.TOAST_SHORT_MS, MusicV3StageCopy.TOAST_LONG_MS)
    assertEquals(true, MusicV3StageCopy.TOAST_SHORT_MS < MusicV3StageCopy.TOAST_LONG_MS)
  }
}
