/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class EngineLocalPhotoRequestsTest {
  private val source = "file:///synthetic/photo with spaces.jpg"
  private val resources = EngineLocalPhotoRequests(listOf(source, "https://source.invalid/photo"), 42)

  @Test fun missingCopyUsesAnOpaqueBoundedResourceWithoutExportingItsSourcePath() {
    val url = resources.urlFor(source, 601)!!
    assertEquals("https://immortal-photo.invalid/42/0/1200", url)
    assertFalse(url.contains("synthetic"))
    assertEquals(source.removePrefix("file://") to 1200, resources.resolve(url))
  }

  @Test fun unknownLocalPathsAndRemotePhotosCannotAcquireAResource() {
    assertNull(resources.urlFor("file:///unlisted.jpg", 600))
    assertNull(resources.urlFor("https://source.invalid/photo", 600))
  }

  @Test fun requestsCannotEscapeTheFrozenPhotoListOrOwner() {
    for (url in listOf(
        "https://immortal-photo.invalid/41/0/1200",
        "https://immortal-photo.invalid/42/1/1200",
        "https://immortal-photo.invalid/42/-1/1200",
        "https://immortal-photo.invalid/42/0/1201",
        "https://immortal-photo.invalid/42/0/999999",
        "https://immortal-photo.invalid/42/0/1200?path=/unlisted.jpg",
        "https://immortal-photo.invalid/42/0/1200#fragment",
        "https://immortal-photo.invalid/42/%30/1200",
        "https://immortal-photo.invalid/42/../../unlisted",
        "https://other.invalid/42/0/1200",
        "http://immortal-photo.invalid/42/0/1200",
        "https://user@immortal-photo.invalid/42/0/1200")) {
      assertNull(url, resources.resolve(url))
    }
  }

  @Test fun bridgeArgumentsUseExistingBoundedTierRules() {
    assertEquals(2400, resources.resolve(resources.urlFor(source, Int.MAX_VALUE)!!)!!.second)
    assertEquals(2400, resources.resolve(resources.urlFor(source, Int.MIN_VALUE)!!)!!.second)
    assertEquals(600, resources.resolve(resources.urlFor(source, 320)!!)!!.second)
  }
}
