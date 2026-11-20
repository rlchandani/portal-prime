/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** Bundled routine pictures: unknown ids fall back to empty copy instead of crashing. */
class RoutinePicturesCoverageTest {
  @Test fun unknownIdsHaveNoLabelOrGroup() {
    assertEquals("", RoutinePictures.defaultLabel("rocket"))
    assertEquals("", RoutinePictures.group("rocket"))
    assertEquals("", RoutinePictures.defaultLabel(""))
  }

  @Test fun idsAreUniqueAndAvatarsAreNotPictures() {
    assertEquals(RoutinePictures.ids.size, RoutinePictures.ids.toSet().size)
    assertTrue(RoutinePictures.avatars.none(RoutinePictures::isValid))
    assertTrue(RoutinePictures.all.all { it.group in setOf("Morning", "Evening", "Any time") })
  }
}
