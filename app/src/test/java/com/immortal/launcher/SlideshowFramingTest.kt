/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SlideshowFramingTest {
  @Test fun wholePhotoAndZoomedEdgePositionsStayWithinValidImageTravel() {
    val whole = SlideshowAdjustment().transform(800, 1200, 600, 400)
    assertEquals(1f / 3f, whole.scale, .0001f)
    assertEquals(0f, whole.y, .0001f)
    val edge = SlideshowAdjustment(3f, 0f, 0f).transform(800, 1200, 600, 400)
    assertEquals(0f, edge.x, .0001f)
    assertEquals(0f, edge.y, .0001f)
  }

  @Test fun malformedAdjustmentsAreRejectedRatherThanProducingNaNTransforms() {
    assertNull(SlideshowAdjustment.validated(Float.NaN, .5f, .5f))
    assertNull(SlideshowAdjustment.validated(4f, .5f, .5f))
    assertNull(SlideshowAdjustment.validated(1f, Float.POSITIVE_INFINITY, .5f))
    assertNull(SlideshowAdjustment.validated(1f, .5f, -.1f))
  }

  @Test fun savedPositionSurvivesRestartAndResetAndNeverPersistsPrivateAssetIdentifier() {
    val dir = Files.createTempDirectory("slideshow-framing").toFile()
    try {
      val key = "https://private.test/photo?token=do-not-persist"
      val adjustment = SlideshowAdjustment(2f, .2f, .8f)
      SlideshowFramingStore(dir).save(key, adjustment)
      assertEquals(adjustment, SlideshowFramingStore(dir).get(key))
      assertFalse(dir.listFiles()!!.single().readText().contains("do-not-persist"))
      SlideshowFramingStore(dir).save(key, null)
      assertNull(SlideshowFramingStore(dir).get(key))
    } finally { dir.deleteRecursively() }
  }

  @Test fun separateWritersMergeAndCorruptFileIsRecoverable() {
    val dir = Files.createTempDirectory("slideshow-framing").toFile()
    try {
      val one = SlideshowFramingStore(dir)
      val two = SlideshowFramingStore(dir)
      one.save("first", SlideshowAdjustment())
      two.save("second", SlideshowAdjustment(2f))
      assertNotNull(SlideshowFramingStore(dir).get("first"))
      assertNotNull(SlideshowFramingStore(dir).get("second"))
      dir.listFiles()!!.single().writeText("{")
      assertNull(SlideshowFramingStore(dir).get("first"))
      one.save("recovered", SlideshowAdjustment())
      assertNotNull(SlideshowFramingStore(dir).get("recovered"))
    } finally { dir.deleteRecursively() }
  }
}
