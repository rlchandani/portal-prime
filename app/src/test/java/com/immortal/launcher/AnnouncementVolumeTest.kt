/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** A received announcement plays at full volume, then the room's own level comes back. */
class AnnouncementVolumeTest {
  private class FakeAudio(var level: Int, val max: Int = 7) : AnnouncementVolume.Audio {
    val sets = mutableListOf<Int>()
    override fun get(stream: Int) = level
    override fun max(stream: Int) = max
    override fun set(stream: Int, index: Int) { level = index; sets += index }
  }

  private val alarm = 4

  @Test fun quietRoomGoesToFullThenBackToItsLevel() {
    val audio = FakeAudio(level = 2)
    val volume = AnnouncementVolume(audio)
    volume.boost(alarm)
    assertEquals(7, audio.level)
    volume.restore()
    assertEquals(2, audio.level)
  }

  @Test fun aMutedRoomStillHearsItAndIsMutedAgainAfter() {
    val audio = FakeAudio(level = 0)
    AnnouncementVolume(audio).apply { boost(alarm); assertEquals(7, audio.level); restore() }
    assertEquals(0, audio.level)
  }

  @Test fun aSecondCallDuringTheBoostDoesNotRememberFullAsTheOldLevel() {
    val audio = FakeAudio(level = 3)
    val volume = AnnouncementVolume(audio)
    volume.boost(alarm)
    volume.boost(alarm)
    volume.restore()
    assertEquals(3, audio.level)
  }

  @Test fun someoneChangingTheVolumeMidAnnouncementKeepsTheirChoice() {
    val audio = FakeAudio(level = 3)
    val volume = AnnouncementVolume(audio)
    volume.boost(alarm)
    audio.level = 5 // a person turned it down with the volume keys
    volume.restore()
    assertEquals(5, audio.level)
  }

  @Test fun alreadyFullTouchesNothing() {
    val audio = FakeAudio(level = 7)
    AnnouncementVolume(audio).apply { boost(alarm); restore() }
    assertEquals(emptyList<Int>(), audio.sets)
  }

  @Test fun restoreWithoutABoostIsANoOp() {
    val audio = FakeAudio(level = 3)
    AnnouncementVolume(audio).restore()
    assertEquals(emptyList<Int>(), audio.sets)
  }
}
