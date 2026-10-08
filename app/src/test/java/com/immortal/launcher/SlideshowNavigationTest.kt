/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher
import org.junit.Assert.*
import org.junit.Test

class SlideshowNavigationTest {
  private val deck = listOf("A", "B", "C", "D", "E")
  @Test fun canceledPreviousDoesNotRemoveAcceptedHistory() {
    val nav = SlideshowNavigation()
    for (key in deck.take(3)) { nav.request(1, deck); nav.commit(key, null) }
    assertEquals(1, nav.request(-1, deck))
    assertEquals(2, nav.request(1, deck))
    nav.commit("C", null)
    assertEquals(1, nav.request(-1, deck))
  }
  @Test fun refreshRebasesPairSuccessorByIdentity() {
    val nav = SlideshowNavigation()
    nav.request(1, deck); nav.commit("A", "B")
    val changed = listOf("X") + deck
    nav.refresh(changed)
    assertEquals(3, nav.request(1, changed))
  }
  @Test fun rapidPairAdvancesKeepTwoPhotoPageSteps() {
    val nav = SlideshowNavigation()
    nav.request(1, deck); nav.commit("A", "B")
    assertEquals(2, nav.request(1, deck))
    assertEquals(4, nav.request(1, deck))
    nav.cancelRequest()
    assertEquals(2, nav.request(1, deck))
  }
  @Test fun removedCurrentPhotoAndSingletonHaveValidNextIndices() {
    val nav = SlideshowNavigation()
    nav.request(1, deck); nav.commit("A", null)
    nav.refresh(listOf("X"))
    assertEquals(0, nav.request(1, listOf("X")))
    nav.commit("X", null)
    assertEquals(0, nav.request(-1, listOf("X")))
  }
}
