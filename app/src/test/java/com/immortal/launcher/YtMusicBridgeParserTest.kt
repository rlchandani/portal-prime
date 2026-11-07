/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMusicBridgeParserTest {
  private val now = 50_000L

  private fun msg(
      title: Any? = "Low tide, early",
      artist: Any? = "Marrow & Vale",
      album: Any? = "Saturday morning",
      art: Any? = "https://lh3.googleusercontent.com/abc=w544-h544",
      duration: Any? = 252.4,
      position: Any? = 85.25,
      state: Any? = "playing",
  ): String {
    fun enc(v: Any?): String = when (v) {
      null -> "null"
      is String -> org.json.JSONObject.quote(v)
      else -> v.toString()
    }
    return """{"v":1,"t":${enc(title)},"a":${enc(artist)},"al":${enc(album)},"art":${enc(art)},""" +
        """"d":${enc(duration)},"p":${enc(position)},"s":${enc(state)}}"""
  }

  @Test fun validPlayingMessage_mapsEveryField() {
    val s = YtMusicBridgeParser.parse(msg(), now)!!
    assertEquals(PlaybackState.PLAYING, s.state)
    assertEquals("Low tide, early", s.title)
    assertEquals("Marrow & Vale", s.artist)
    assertEquals("Saturday morning", s.album)
    assertEquals("https://lh3.googleusercontent.com/abc=w544-h544", s.artUrl)
    assertEquals(252_400L, s.durationMs)
    assertEquals(85_250L, s.positionMs)
    assertEquals(now, s.positionUpdatedElapsedMs)
    assertEquals(SOURCE_EMBEDDED_YT_MUSIC, s.source)
    assertTrue(s.canSeek)
  }

  @Test fun pausedAndEndedStates() {
    assertEquals(PlaybackState.PAUSED, YtMusicBridgeParser.parse(msg(state = "paused"), now)!!.state)
    assertEquals(PlaybackState.STOPPED, YtMusicBridgeParser.parse(msg(state = "ended"), now)!!.state)
  }

  @Test fun unknownState_isRejected() {
    assertNull(YtMusicBridgeParser.parse(msg(state = "exploding"), now))
    assertNull(YtMusicBridgeParser.parse(msg(state = 3), now))
  }

  @Test fun missingTitle_meansNothingToShow() {
    assertNull(YtMusicBridgeParser.parse(msg(title = null), now))
    assertNull(YtMusicBridgeParser.parse(msg(title = "   "), now))
    assertNull(YtMusicBridgeParser.parse("""{"v":1,"s":"playing"}""", now))
  }

  @Test fun missingOptionalFields_defaultSafely() {
    val s = YtMusicBridgeParser.parse("""{"v":1,"t":"Song","s":"paused"}""", now)!!
    assertEquals("", s.artist)
    assertEquals("", s.album)
    assertEquals("", s.artUrl)
    assertEquals(0L, s.durationMs)
    assertEquals(0L, s.positionMs)
    assertFalse(s.canSeek)
  }

  @Test fun malformedOrNonObjectInput_isRejected() {
    assertNull(YtMusicBridgeParser.parse(null, now))
    assertNull(YtMusicBridgeParser.parse("", now))
    assertNull(YtMusicBridgeParser.parse("not json", now))
    assertNull(YtMusicBridgeParser.parse("[1,2]", now))
    assertNull(YtMusicBridgeParser.parse("""{"v":2,"t":"Song","s":"playing"}""", now))
  }

  @Test fun oversizedMessage_isRejectedBeforeParsing() {
    val huge = msg(album = "x".repeat(YtMusicBridgeParser.MAX_MESSAGE_CHARS))
    assertNull(YtMusicBridgeParser.parse(huge, now))
  }

  @Test fun longText_isTruncatedAndControlCharsStripped() {
    val s = YtMusicBridgeParser.parse(msg(title = "A\u0000B\nC" + "y".repeat(2000), artist = "Ar‮tist"), now)!!
    assertTrue(s.title.length <= YtMusicBridgeParser.MAX_TEXT_CHARS)
    assertTrue(s.title.startsWith("AB C"))
    assertEquals("Artist", s.artist)
  }

  @Test fun wrongTypedText_isIgnored() {
    val s = YtMusicBridgeParser.parse(msg(artist = 42, album = true), now)!!
    assertEquals("", s.artist)
    assertEquals("", s.album)
  }

  @Test fun hostileNumbers_areNeutralised() {
    val neg = YtMusicBridgeParser.parse(msg(duration = -5, position = -1), now)!!
    assertEquals(0L, neg.durationMs)
    assertEquals(0L, neg.positionMs)
    assertFalse(neg.canSeek)
    val strings = YtMusicBridgeParser.parse(msg(duration = "\"NaN\"", position = "\"Infinity\""), now)!!
    assertEquals(0L, strings.durationMs)
    assertEquals(0L, strings.positionMs)
    val huge = YtMusicBridgeParser.parse(msg(duration = 1e300, position = 1e300), now)!!
    assertEquals(0L, huge.durationMs) // beyond 24 h is treated as live / unknown
    assertEquals(0L, huge.positionMs)
  }

  @Test fun positionPastDuration_isClamped() {
    val s = YtMusicBridgeParser.parse(msg(duration = 100, position = 250), now)!!
    assertEquals(100_000L, s.positionMs)
  }

  @Test fun nonHttpsArt_isDropped() {
    assertEquals("", YtMusicBridgeParser.parse(msg(art = "http://lh3.googleusercontent.com/a"), now)!!.artUrl)
    assertEquals("", YtMusicBridgeParser.parse(msg(art = "javascript:alert(1)"), now)!!.artUrl)
    assertEquals("", YtMusicBridgeParser.parse(msg(art = "data:image/png;base64,AAAA"), now)!!.artUrl)
    assertEquals("", YtMusicBridgeParser.parse(msg(art = "file:///sdcard/x.jpg"), now)!!.artUrl)
  }

  @Test fun artFromUnexpectedHosts_isDropped() {
    assertEquals("", YtMusicBridgeParser.parse(msg(art = "https://evil.example/a.jpg"), now)!!.artUrl)
    assertEquals("", YtMusicBridgeParser.parse(msg(art = "https://googleusercontent.com.evil.example/a"), now)!!.artUrl)
    assertEquals("", YtMusicBridgeParser.parse(msg(art = "https://user:pw@lh3.googleusercontent.com/a"), now)!!.artUrl)
    assertEquals("", YtMusicBridgeParser.parse(msg(art = "https://lh3.googleusercontent.com/" + "a".repeat(3000)), now)!!.artUrl)
    assertEquals("https://i.ytimg.com/vi/abc/hqdefault.jpg",
        YtMusicBridgeParser.parse(msg(art = "https://i.ytimg.com/vi/abc/hqdefault.jpg"), now)!!.artUrl)
    assertEquals("https://yt3.ggpht.com/abc",
        YtMusicBridgeParser.parse(msg(art = "https://yt3.ggpht.com/abc"), now)!!.artUrl)
  }

  @Test fun shouldPublish_onMetadataOrStateChange() {
    val a = YtMusicBridgeParser.parse(msg(), now)!!
    assertTrue(YtMusicBridgeParser.shouldPublish(null, a, now))
    assertTrue(YtMusicBridgeParser.shouldPublish(a, a.copy(state = PlaybackState.PAUSED), now))
    assertTrue(YtMusicBridgeParser.shouldPublish(a, a.copy(title = "Next"), now))
    assertTrue(YtMusicBridgeParser.shouldPublish(a, a.copy(artUrl = ""), now))
    assertTrue(YtMusicBridgeParser.shouldPublish(a, a.copy(durationMs = 1), now))
  }

  @Test fun shouldPublish_skipsPositionThatMatchesExtrapolation() {
    val a = YtMusicBridgeParser.parse(msg(), now)!!
    val tick = a.copy(positionMs = a.positionMs + 1_000, positionUpdatedElapsedMs = now + 1_000)
    assertFalse(YtMusicBridgeParser.shouldPublish(a, tick, now + 1_000))
    val seeked = a.copy(positionMs = 10_000, positionUpdatedElapsedMs = now + 1_000)
    assertTrue(YtMusicBridgeParser.shouldPublish(a, seeked, now + 1_000))
    val pausedA = a.copy(state = PlaybackState.PAUSED)
    assertFalse(YtMusicBridgeParser.shouldPublish(pausedA, pausedA.copy(positionUpdatedElapsedMs = now + 5_000), now + 5_000))
  }

  @Test fun commandScripts_areFixedAndNumericOnly() {
    assertEquals("window.__portalYtm&&window.__portalYtm.cmd('play');",
        YtMusicBridgeParser.commandScript(YtMusicCommand.PLAY))
    assertEquals("window.__portalYtm&&window.__portalYtm.cmd('next');",
        YtMusicBridgeParser.commandScript(YtMusicCommand.NEXT))
    assertEquals("window.__portalYtm&&window.__portalYtm.cmd('seek',12.345);",
        YtMusicBridgeParser.commandScript(YtMusicCommand.SEEK, 12_345))
    assertEquals("window.__portalYtm&&window.__portalYtm.cmd('seek',0.0);",
        YtMusicBridgeParser.commandScript(YtMusicCommand.SEEK, -9))
  }
}
