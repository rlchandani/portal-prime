/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MusicPhotoRetentionTest {
  @Test fun thirtyLayoutCyclesCreateAndStartOnePhotoController() {
    var created = 0
    var starts = 0
    var stops = 0
    val active = mutableListOf<Boolean>()
    val retained = MusicPhotoRetention(
        create = { created++; Any() }, start = { starts++ },
        setActive = { _, value -> active += value }, stop = { stops++ })
    var first: Any? = null
    repeat(30) {
      val frame = retained.frame()
      if (first == null) first = frame else assertSame(first, frame)
      retained.setVisible(true)
      retained.start()
      retained.setVisible(false)
    }
    assertEquals(1, created)
    assertEquals(1, starts)
    assertEquals(0, stops)
    assertFalse(active.last())
    retained.close()
    assertEquals(1, stops)
  }

  @Test fun musicWithoutPhotosDoesNotAllocatePhotoViewsOrWorkers() {
    var created = 0
    val retained = MusicPhotoRetention(
        create = { created++; Any() }, start = {}, setActive = { _, _ -> }, stop = {})
    retained.setVisible(false)
    retained.close()
    assertEquals(0, created)
  }

  @Test fun lifecycleChangesCannotRestartThePhotoController() {
    var starts = 0
    val active = mutableListOf<Boolean>()
    val retained = MusicPhotoRetention(
        create = { Any() }, start = { starts++ },
        setActive = { _, value -> active += value }, stop = {})
    retained.frame()
    retained.start()
    retained.setVisible(true)
    retained.setVisible(false)
    retained.setVisible(true)
    assertEquals(1, starts)
    assertEquals(listOf(false, true, false, true), active)
  }

  @Test fun duplicateCloseStopsOnceAndCannotResurrectAClosedFrame() {
    var stops = 0
    val retained = MusicPhotoRetention(
        create = { Any() }, start = {}, setActive = { _, _ -> }, stop = { stops++ })
    retained.frame()
    retained.start()
    retained.close()
    retained.close()
    retained.setVisible(true)
    assertEquals(1, stops)
    try {
      retained.frame()
      fail("A closed photo session must not be recreated")
    } catch (_: IllegalStateException) {}
  }
}
