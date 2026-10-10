/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class FaceRendererWorkStateTest {
  @Test fun `work is admitted only after start and repeated start keeps the same session`() {
    val work = FaceRendererWorkState()
    assertNull(work.token())
    assertTrue(work.start())
    val token = work.token()!!
    assertTrue(work.isCurrent(token))
    assertFalse(work.start())
    assertEquals(token, work.token())
  }

  @Test fun `host may hide before start and later activate the retained renderer`() {
    val work = FaceRendererWorkState()
    assertTrue(work.setHostActive(false))
    assertTrue(work.start())
    assertFalse(work.isActive)
    assertNull(work.token())
    assertTrue(work.setHostActive(true))
    assertTrue(work.isActive)
    assertNotNull(work.token())
  }

  @Test fun `hidden work cannot commit and stays obsolete after resume`() {
    val work = FaceRendererWorkState()
    work.start()
    val beforeHide = work.token()!!
    work.setHostActive(false)
    assertFalse(work.isCurrent(beforeHide))
    assertNull(work.token())
    work.setHostActive(true)
    val resumed = work.token()!!
    assertTrue(work.isCurrent(resumed))
    assertFalse(work.isCurrent(beforeHide))
  }

  @Test fun `repeated active or hidden requests do not create more sessions`() {
    val work = FaceRendererWorkState()
    work.start()
    val active = work.token()!!
    assertFalse(work.setHostActive(true))
    assertTrue(work.isCurrent(active))
    assertTrue(work.setHostActive(false))
    assertFalse(work.setHostActive(false))
    assertTrue(work.setHostActive(true))
    val resumed = work.token()!!
    assertFalse(work.setHostActive(true))
    assertTrue(work.isCurrent(resumed))
  }

  @Test fun `rapid hide resume cycles admit only the newest callbacks`() {
    val work = FaceRendererWorkState()
    work.start()
    val oldTokens = ArrayList<Long>()
    repeat(20) {
      oldTokens.add(work.token()!!)
      work.setHostActive(false)
      work.setHostActive(true)
    }
    assertTrue(work.isCurrent(work.token()!!))
    oldTokens.forEach { assertFalse(work.isCurrent(it)) }
  }

  @Test fun `stop while active is terminal and invalidates outstanding work`() {
    val work = FaceRendererWorkState()
    work.start()
    val token = work.token()!!
    assertTrue(work.stop())
    assertTrue(work.isStopped)
    assertFalse(work.isCurrent(token))
    assertNull(work.token())
    assertFalse(work.stop())
    assertFalse(work.start())
    assertFalse(work.setHostActive(false))
    assertFalse(work.setHostActive(true))
    assertFalse(work.isActive)
  }

  @Test fun `stop before start or while hidden cannot be undone`() {
    for (startHidden in listOf(false, true)) {
      val work = FaceRendererWorkState()
      if (startHidden) {
        work.start()
        work.setHostActive(false)
      }
      work.stop()
      assertFalse(work.start())
      assertFalse(work.setHostActive(true))
      assertNull(work.token())
    }
  }
}
