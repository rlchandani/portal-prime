/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.immortal.launcher.SlideshowFaceMetadata.Box
import com.immortal.launcher.SlideshowFaceMetadata.Bounds
import com.immortal.launcher.SlideshowFaceMetadata.Entry
import com.immortal.launcher.SlideshowFaceMetadata.Status
import org.junit.Assert.*
import org.junit.Test

class SlideshowFaceMetadataTest {
  private val key = SlideshowFaceMetadata.key("asset:42:revision:2")
  private val now = 2_000_000_000_000L
  private fun entry(status: Status = Status.FACES, at: Long = now) =
      Entry(key, status, at, 640, 480,
          if (status == Status.FACES) listOf(Bounds(.1f, .2f, .4f, .6f)) else emptyList())

  @Test fun `metadata contains raw detector boxes with geometry as the sole padding policy`() {
    val region = SlideshowFaceMetadata.normalized(Box(100, 100, 200, 200), 400, 400)!!
    assertEquals(.25f, region.left, .00001f)
    assertEquals(.25f, region.top, .00001f)
    assertEquals(.50f, region.right, .00001f)
    assertEquals(.50f, region.bottom, .00001f)
  }

  @Test fun `normalization clamps to bounds and tiny group faces are retained`() {
    assertEquals(Bounds(0f, 0f, 1f, 1f),
        SlideshowFaceMetadata.normalized(Box(-10, -5, 650, 490), 640, 480))
    assertNotNull(SlideshowFaceMetadata.normalized(Box(10, 10, 12, 12), 640, 480))
  }

  @Test fun `invalid or wholly outside boxes are rejected before normalization`() {
    for (box in listOf(Box(4, 2, 4, 5), Box(10, 10, 4, 20),
        Box(-30, 10, -1, 20), Box(641, 10, 650, 20), Box(1, -5, 3, 0))) {
      assertNull(SlideshowFaceMetadata.normalized(box, 640, 480))
    }
    assertNull(SlideshowFaceMetadata.normalized(Box(1, 1, 3, 3), 0, 480))
  }

  @Test fun `extreme detector coordinates do not overflow`() {
    assertEquals(Bounds(0f, 0f, 1f, 1f),
        SlideshowFaceMetadata.normalized(Box(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE), 640, 480))
  }

  @Test fun `derivative dimensions are bounded without upscaling or zero edges`() {
    assertEquals(640 to 480, SlideshowFaceMetadata.targetSize(4000, 3000))
    assertEquals(480 to 640, SlideshowFaceMetadata.targetSize(3000, 4000))
    assertEquals(12 to 17, SlideshowFaceMetadata.targetSize(12, 17))
    assertEquals(640 to 1, SlideshowFaceMetadata.targetSize(Int.MAX_VALUE, 1))
    assertEquals(1 to 640, SlideshowFaceMetadata.targetSize(1, Int.MAX_VALUE))
  }

  @Test fun `exact supplied keys preserve authorization and revision distinctions`() {
    val url = "https://user:secret@example.test/photo?id=1&auth=one#rev"
    val digest = SlideshowFaceMetadata.key(url)
    assertTrue(digest.matches(Regex("[a-f0-9]{64}")))
    assertEquals(digest, SlideshowFaceMetadata.key(url))
    assertNotEquals(digest, SlideshowFaceMetadata.key(url.replace("auth=one", "auth=two")))
    assertNotEquals(key, SlideshowFaceMetadata.key("asset:42:revision:3"))
  }

  @Test fun `positive negative failure and pending TTLs expire at actual boundary`() {
    for ((status, ttl) in listOf(Status.FACES to 30 * SlideshowFaceMetadata.DAY_MS,
        Status.NO_FACE to 7 * SlideshowFaceMetadata.DAY_MS,
        Status.FAILURE to 600_000L, Status.PENDING to 120_000L)) {
      assertTrue(SlideshowFaceMetadata.fresh(entry(status, now - ttl + 1), key, now))
      assertFalse(SlideshowFaceMetadata.fresh(entry(status, now - ttl), key, now))
    }
  }

  @Test fun `clock rollback schema model policy and revision invalidate metadata`() {
    val original = entry()
    for (changed in listOf(original.copy(analyzedAt = now + 1),
        original.copy(schema = 0), original.copy(model = "other"),
        original.copy(policy = "old"), original.copy(revisionKey = SlideshowFaceMetadata.key("old")))) {
      assertFalse(SlideshowFaceMetadata.fresh(changed, key, now))
    }
  }

  @Test fun `previous padded cache policy can never be interpreted as raw boxes`() {
    assertFalse(SlideshowFaceMetadata.fresh(
        entry().copy(policy = "upright-640-pad20-hair20-v1"), key, now))
  }

  @Test fun `corrupt dimensions status and nonfinite regions never qualify as fresh`() {
    val original = entry()
    for (changed in listOf(original.copy(width = 0), original.copy(height = 641),
        original.copy(regions = emptyList()), original.copy(status = Status.NO_FACE),
        original.copy(regions = listOf(Bounds(Float.NaN, 0f, 1f, 1f))),
        original.copy(regions = listOf(Bounds(0f, 0f, Float.POSITIVE_INFINITY, 1f))),
        original.copy(regions = listOf(Bounds(.6f, 0f, .4f, 1f))),
        original.copy(regions = listOf(Bounds(-.1f, 0f, 1f, 1f))),
        original.copy(regions = List(129) { Bounds(0f, 0f, 1f, 1f) }))) {
      assertFalse(SlideshowFaceMetadata.fresh(changed, key, now))
    }
  }
}
