/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** The words on the Ask card and the Automatic banner. */
class FollowMeCopyTest {
  @Test fun askCard() {
    assertEquals("Continue Clair de Lune here?", FollowMeCopy.askTitle("Clair de Lune"))
    assertEquals("Debussy · from Family Area", FollowMeCopy.askLine("Debussy", "Family Area"))
    assertEquals("from Family Area", FollowMeCopy.askLine(" ", "Family Area"))
  }

  @Test fun automaticBanner() {
    assertEquals("Music followed you from Family Area", FollowMeCopy.followed("Family Area"))
  }
}
