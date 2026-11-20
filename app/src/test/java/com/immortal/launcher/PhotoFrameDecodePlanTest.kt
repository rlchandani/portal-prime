/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decode routing and sizes on a fake decoder, and the built-in feed's URLs. */
class PhotoFrameDecodePlanTest {
  private val decoder = FakePhotoFrameDecoder(
      sizes = mapOf("/a.jpg" to (4000 to 3000), "/b.jpg" to (4000 to 3000)), orientations = mapOf("/b.jpg" to 6))
  private val plan = PhotoFrameDecodePlan(decoder)
  private val identities = ArrayList<Pair<String, String>>()
  private val identify = { b: String, id: String -> identities += b to id; Unit }

  @Test fun classicDecodesTheFileItselfAndNeverIdentifies() {
    assertEquals("file:/x.jpg@2400", plan.file("/x.jpg", 2400, nativeStage = false, identify))
    assertEquals("stream@1400", plan.stream(ByteArray(3).inputStream(), 1400, nativeStage = false, identify))
    assertEquals("bytes:4@2400", plan.bytes(ByteArray(4), 2400, nativeStage = false, identify))
    assertEquals(emptyList<Pair<String, String>>(), identities)
  }

  @Test fun theNativeStageReadsBytesOnceAndRecordsTheirIdentity() {
    val dir = Files.createTempDirectory("decode-plan").toFile()
    try {
      val f = File(dir, "p.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
      assertEquals("bytes:3@1400", plan.file(f.path, 1400, nativeStage = true, identify))
      assertEquals(listOf("read 3", "bytes 3 1400"), decoder.calls)
      assertEquals("bytes:3@1400" to PhotoFrameDecodePlan.encodedIdentity(byteArrayOf(1, 2, 3)), identities.single())
      assertNull("a missing file is no photo", plan.file(File(dir, "gone.jpg").path, 1400, true, identify))
    } finally { dir.deleteRecursively() }
  }

  @Test fun anUndecodableImageIsNotIdentified() {
    decoder.bytesResult = { _, _ -> null }
    assertNull(plan.bytes(ByteArray(5), 2400, nativeStage = true, identify))
    assertEquals(emptyList<Pair<String, String>>(), identities)
  }

  @Test fun theEncodedIdentityIsTheSha256OfTheBytes() {
    assertEquals("encoded:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", PhotoFrameDecodePlan.encodedIdentity(ByteArray(0)))
    assertTrue(PhotoFrameDecodePlan.encodedIdentity(byteArrayOf(1)) != PhotoFrameDecodePlan.encodedIdentity(byteArrayOf(2)))
  }

  @Test fun quarterTurnsSwapTheOrientedSize() {
    assertEquals(4000 to 3000, plan.orientedSize("/a.jpg"))
    assertEquals(3000 to 4000, plan.orientedSize("/b.jpg"))
    assertEquals(listOf(5, 6, 7, 8), (1..8).filter { PhotoFrameDecodePlan.swapsAxes(it) })
    assertNull(plan.orientedSize("/unreadable.jpg"))
    assertTrue("EXIF is read only for a readable header", "orientation /unreadable.jpg" !in decoder.calls)
  }

  @Test fun testScenesAreEverySceneTwiceAtTheirEdge() {
    assertEquals(ScreensaverTestSource.scenes.size * 2, PhotoFrameDecodePlan.testSceneCount())
    assertEquals(1600, PhotoFrameDecodePlan.TEST_SCENE_EDGE)
    assertEquals(2400, PhotoFrameDecodePlan.DEFAULT_EDGE)
  }

  @Test fun theEngineBatchIsCappedToThePanelsLongEdge() {
    val plus = PhotoFrameFeedUrls.engineBatch(1920, 1080)
    assertEquals(17, plus.size)
    assertEquals("https://picsum.photos/seed/ridge/1920/1280", plus.first())
    assertEquals("https://picsum.photos/seed/pano2/1920/640", plus.last())
    assertEquals("https://picsum.photos/seed/ridge/1280/853", PhotoFrameFeedUrls.engineBatch(800, 1280).first())
    assertEquals("https://picsum.photos/seed/ridge/2200/1467", PhotoFrameFeedUrls.engineBatch(3840, 2160).first())
    assertEquals("https://picsum.photos/seed/ridge/1080/720", PhotoFrameFeedUrls.engineBatch(640, 480).first())
  }

  @Test fun theClassicFeedAsksForTheScreensShape() {
    assertEquals("https://picsum.photos/1920/1080?random=42", PhotoFrameFeedUrls.picsum(1920, 1080, 42))
    assertEquals("portrait", PhotoFrameFeedUrls.unsplashOrientation(800, 1280))
    assertEquals("landscape", PhotoFrameFeedUrls.unsplashOrientation(1280, 1280))
    assertEquals("https://api.unsplash.com/photos/random?orientation=landscape&query=nature&client_id=k",
        PhotoFrameFeedUrls.unsplash("landscape", "nature", "k"))
    assertTrue(PhotoFrameFeedUrls.wikimedia(3000).endsWith("&iiurlwidth=1920"))
    assertTrue(PhotoFrameFeedUrls.wikimedia(320).endsWith("&iiurlwidth=640"))
    assertTrue(PhotoFrameFeedUrls.wikimedia(1280).contains("Category:Featured_pictures_of_landscapes"))
  }
}
