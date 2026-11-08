/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtPlayerEventParserTest {
  private fun parse(raw: String?) = YtPlayerEventParser.parse(raw, elapsedMs = 777)

  @Test fun parsesReadyStateErrorAndFlags() {
    assertEquals(YtPlayerEvent.Ready, parse("""{"v":1,"e":"ready"}"""))
    assertEquals(YtPlayerEvent.StateChanged(YtPlayerStatus.PLAYING), parse("""{"v":1,"e":"state","s":1}"""))
    assertEquals(YtPlayerEvent.StateChanged(YtPlayerStatus.CUED), parse("""{"v":1,"e":"state","s":5}"""))
    assertEquals(YtPlayerEvent.StateChanged(YtPlayerStatus.UNSTARTED), parse("""{"v":1,"e":"state","s":-1}"""))
    assertEquals(YtPlayerEvent.Error(150), parse("""{"v":1,"e":"error","c":150}"""))
    assertEquals(YtPlayerEvent.AutoplayBlocked, parse("""{"v":1,"e":"autoplayBlocked"}"""))
    assertEquals(YtPlayerEvent.ApiUnavailable, parse("""{"v":1,"e":"apiError"}"""))
  }

  @Test fun parsesProgressInMilliseconds() {
    assertEquals(YtPlayerEvent.Progress(12_500, 200_000, "abcdefghijk", 777),
        parse("""{"v":1,"e":"progress","t":12.5,"d":200,"id":"abcdefghijk"}"""))
  }

  @Test fun progress_carriesCleanedTitleAndAuthor_forPlaceholderRows() {
    assertEquals(YtPlayerEvent.Progress(1_000, 200_000, "abcdefghijk", 777, "Saltwater hours", "Marrow & Vale"),
        parse("""{"v":1,"e":"progress","t":1,"d":200,"id":"abcdefghijk","ti":" Saltwater hours\u0007","au":"Marrow & Vale"}"""))
    assertEquals(YtPlayerEvent.Progress(1_000, 200_000, null, 777),
        parse("""{"v":1,"e":"progress","t":1,"d":200,"ti":"  ","au":5}"""))
  }

  @Test fun progress_clampsPositionToDuration_andDropsBadVideoId() {
    assertEquals(YtPlayerEvent.Progress(10_000, 10_000, null, 777),
        parse("""{"v":1,"e":"progress","t":99,"d":10,"id":"<script>"}"""))
  }

  @Test fun progress_rejectsNonFiniteOrNegativeOrHuge() {
    assertNull(parse("""{"v":1,"e":"progress","t":-1,"d":10}"""))
    assertNull(parse("""{"v":1,"e":"progress","t":"NaN","d":10}"""))
    assertNull(parse("""{"v":1,"e":"progress","t":1,"d":1e12}"""))
  }

  @Test fun rejectsMalformedUnknownAndOversized() {
    assertNull(parse(null))
    assertNull(parse(""))
    assertNull(parse("nope"))
    assertNull(parse("""{"v":2,"e":"ready"}"""))
    assertNull(parse("""{"v":1,"e":"launchMissiles"}"""))
    assertNull(parse("""{"v":1,"e":"state","s":42}"""))
    assertNull(parse("""{"v":1,"e":"error","c":"x"}"""))
    assertNull(parse("""{"v":1,"e":"ready","pad":"${"x".repeat(YtPlayerEventParser.MAX_MESSAGE_CHARS)}"}"""))
  }
}
