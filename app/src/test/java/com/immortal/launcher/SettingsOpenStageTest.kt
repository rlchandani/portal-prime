/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOpenStageTest {
  @Test fun firstFrame_isOnlyTheSkeleton() {
    assertFalse(SettingsOpenStage.SKELETON.showsMaster)
    assertFalse(SettingsOpenStage.SKELETON.showsDetail)
  }

  @Test fun stagesAdvanceMasterThenDetail_thenStop() {
    assertEquals(SettingsOpenStage.MASTER, SettingsOpenStage.SKELETON.next())
    assertTrue(SettingsOpenStage.MASTER.showsMaster && !SettingsOpenStage.MASTER.showsDetail)
    assertEquals(SettingsOpenStage.COMPLETE, SettingsOpenStage.MASTER.next())
    assertTrue(SettingsOpenStage.COMPLETE.showsMaster && SettingsOpenStage.COMPLETE.showsDetail)
    assertNull(SettingsOpenStage.COMPLETE.next())
  }
}
