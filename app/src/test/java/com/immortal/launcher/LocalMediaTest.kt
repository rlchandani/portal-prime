/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMediaTest {

  @Test
  fun classify_byExtension_caseInsensitive() {
    assertEquals(LocalMedia.Kind.IMAGE, LocalMedia.classify("beach.jpg"))
    assertEquals(LocalMedia.Kind.IMAGE, LocalMedia.classify("SUNSET.JPEG"))
    assertEquals(LocalMedia.Kind.IMAGE, LocalMedia.classify("pic.png"))
    assertEquals(LocalMedia.Kind.VIDEO, LocalMedia.classify("clip.mp4"))
    assertEquals(LocalMedia.Kind.VIDEO, LocalMedia.classify("home.MOV"))
    assertEquals(LocalMedia.Kind.OTHER, LocalMedia.classify("notes.pdf"))
    assertEquals(LocalMedia.Kind.OTHER, LocalMedia.classify("README"))
  }

  @Test
  fun displayName_takesTheLeafFolder() {
    assertEquals("Trip", LocalMedia.displayName("/storage/emulated/0/DCIM/Trip"))
    assertEquals("Trip", LocalMedia.displayName("/storage/emulated/0/DCIM/Trip/"))
    assertEquals("0", LocalMedia.displayName("/storage/emulated/0"))
    assertEquals("1A2B-3C4D", LocalMedia.displayName("/storage/1A2B-3C4D"))
  }
}
