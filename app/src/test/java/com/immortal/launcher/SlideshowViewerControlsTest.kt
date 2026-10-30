/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowViewerControlsTest {
  @Test fun gallerySpreadCannotEditCropsEvenWithAVisiblePhoto() {
    // A saved crop is ignored by normal spread rendering, so offering Adjust is misleading.
    assertFalse(slideshowCropEditingAllowed("gallerySpread", stageVisible = true, hasPhoto = true))
  }

  @Test fun otherPhotoPresentationsRetainCropEditing() {
    listOf("smartFrame", "portraitPair", "wholePhoto", "galleryPrint", "classic").forEach { mode ->
      assertTrue(mode, slideshowCropEditingAllowed(mode, stageVisible = true, hasPhoto = true))
    }
  }

  @Test fun hiddenStageOrMissingPhotoStillCannotEnterTheEditor() {
    listOf("smartFrame", "portraitPair", "wholePhoto", "galleryPrint", "gallerySpread", "classic")
        .forEach { mode ->
          assertFalse(mode, slideshowCropEditingAllowed(mode, stageVisible = false, hasPhoto = true))
          assertFalse(mode, slideshowCropEditingAllowed(mode, stageVisible = true, hasPhoto = false))
          assertFalse(mode, slideshowCropEditingAllowed(mode, stageVisible = false, hasPhoto = false))
        }
  }
}
