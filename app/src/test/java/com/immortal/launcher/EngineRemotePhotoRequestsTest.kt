/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class EngineRemotePhotoRequestsTest {
  private val source = "https://synthetic.invalid/asset.jpg?signed=dummy"
  private val requests = EngineRemotePhotoRequests(listOf(source, "file:///local.jpg"), 7)

  @Test fun remoteResourcesHideSourceQueriesAndRetainRequestedTier() {
    val url = requests.urlFor(source, 1000)!!
    assertEquals("https://immortal-remote-photo.invalid/7/0/1200", url)
    assertFalse(url.contains("signed"))
    assertEquals(source to 1200, requests.resolve(url))
  }

  @Test fun unknownSourcesAndNonHttpUrlsCannotAcquireAResource() {
    for (url in listOf("https://unknown.invalid/asset.jpg", "file:///local.jpg", "data:image/jpeg;base64,dummy"))
      assertNull(requests.urlFor(url, 2400))
  }

  @Test fun resourcesCannotChangeOwnerSourceOrPixelTier() {
    for (url in listOf(
        "https://immortal-remote-photo.invalid/8/0/1200",
        "https://immortal-remote-photo.invalid/7/1/1200",
        "https://immortal-remote-photo.invalid/7/-1/1200",
        "https://immortal-remote-photo.invalid/7/0/9999",
        "https://immortal-remote-photo.invalid/7/0/1200?url=https://other.invalid",
        "https://immortal-remote-photo.invalid/7/0/1200#fragment",
        "http://immortal-remote-photo.invalid/7/0/1200",
        "https://user@immortal-remote-photo.invalid/7/0/1200"))
      assertNull(url, requests.resolve(url))
  }
}
