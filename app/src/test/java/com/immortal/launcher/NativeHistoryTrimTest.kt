/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: on a Portal+ the slideshow stopped after two photos (budget starvation). */
class NativeHistoryTrimTest {
  private val mib = 1024L * 1024
  // A 2400 x 1600 ARGB scene: the Portal+ decode edge for a 3:2 photo.
  private val plusScene = 2400L * 1600 * 4
  private val decodeScratch = 24 * mib

  @Test fun aFreshLoadKeepsOnlyThePhotoOnScreen() {
    assertEquals(listOf("b"), NativeHistoryTrim.forFreshLoad(listOf("a", "b"), index = 1))
    assertEquals(listOf("a"), NativeHistoryTrim.forFreshLoad(listOf("a"), index = 0))
    assertEquals(emptyList<String>(), NativeHistoryTrim.forFreshLoad(emptyList<String>(), index = -1))
    assertEquals(emptyList<String>(), NativeHistoryTrim.forFreshLoad(listOf("a", "b"), index = 5))
  }

  @Test fun twoPlusScenesLeaveNoRoomForTheDecoderButOneDoes() {
    val budget = SlideshowPixelBudget()
    val history = listOf(Any(), Any()).map { SlideshowPixelBudget.Entry(it, plusScene) }
    budget.setOwner("history", history)
    var polls = 0
    // The old flow: the reservation can never be met, so it only ends when the request does.
    assertNull(budget.reserve(decodeScratch) { polls++ < 3 })
    budget.setOwner("history", NativeHistoryTrim.forFreshLoad(history, index = 1))
    val token = budget.reserve(decodeScratch) { true }
    assertNotNull(token)
    assertTrue(budget.used() <= 48 * mib)
    budget.release(token!!)
  }
}
