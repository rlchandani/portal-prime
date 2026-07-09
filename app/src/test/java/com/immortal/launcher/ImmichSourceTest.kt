/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class ImmichSourceTest {

  @Test
  fun normalizeBase_stripsTrailingSlashAndApiSuffix() {
    assertEquals("http://immich:2283", ImmichSource.normalizeBase("http://immich:2283/"))
    assertEquals("http://immich:2283", ImmichSource.normalizeBase(" http://immich:2283/api "))
    assertEquals("https://photos.example", ImmichSource.normalizeBase("https://photos.example/API/"))
  }

  @Test
  fun previewUrl_pointsAtScreenSizedThumbnail() {
    assertEquals(
        "http://immich:2283/api/assets/abc/thumbnail?size=preview",
        ImmichSource.previewUrl("http://immich:2283/", "abc"))
  }

  @Test
  fun playbackUrl_pointsAtVideoPlaybackEndpoint() {
    assertEquals(
        "http://immich:2283/api/assets/abc/video/playback",
        ImmichSource.playbackUrl("http://immich:2283/", "abc"))
  }

  @Test
  fun authHeaders_carryTrimmedApiKey() {
    assertEquals(mapOf("x-api-key" to "secret"), ImmichSource.authHeaders(" secret "))
  }
}
