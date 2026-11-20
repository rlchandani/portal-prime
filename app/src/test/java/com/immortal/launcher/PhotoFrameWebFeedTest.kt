/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Commons listing parser and the transport's request generations. */
class PhotoFrameWebFeedTest {
  @Test fun commonsListingsPreferThumbnailsAndSkipEmptyEntries() {
    val json = """{"query":{"pages":{
      "1":{"imageinfo":[{"thumburl":"https://upload.test/thumb/a.jpg","url":"https://upload.test/a.jpg"}]},
      "2":{"imageinfo":[{"url":"https://upload.test/b.jpg"}]},
      "3":{"imageinfo":[{}]},
      "4":{"title":"no info"}}}}"""
    assertEquals(listOf("https://upload.test/thumb/a.jpg", "https://upload.test/b.jpg"), PhotoFrameWebFeed.wikimediaThumbnails(json))
    assertEquals(emptyList<String>(), PhotoFrameWebFeed.wikimediaThumbnails("""{"batchcomplete":""}"""))
  }

  @Test fun cancellingSupersedesEveryOpenRequestAndStoppingEndsThemAll() {
    var stopped = false
    val transport = PhotoFrameTransport { stopped }
    try {
      val first = transport.generation.get()
      assertTrue(transport.current(first))
      transport.cancelAll()
      assertFalse(transport.current(first))
      assertTrue(transport.current(transport.generation.get()))
      val stream = ByteArray(1).inputStream()
      assertTrue(transport.register(stream) { true })
      assertFalse("a superseded request isn't tracked", transport.register(stream) { false })
      transport.unregister(stream)
      stopped = true
      assertFalse(transport.current(transport.generation.get()))
    } finally { transport.shutdown() }
  }
}
