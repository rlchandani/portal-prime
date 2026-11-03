/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Actual legacy selection with generated socket bytes; no server, credentials or playback. */
class MaControlPlayerSelectionTest {
  @Test fun metadataExactIdWinsRegardlessOfNameMatchOrder() {
    val own = player("portal-a", "Family", "Own track")
    val decoy = player("other-b", "Family", "Other track")
    for (rows in listOf(listOf(own, decoy), listOf(decoy, own))) {
      assertEquals(NowPlayingState(PlaybackState.PLAYING, title = "Own track", source = "ma"),
          metadata(reply(rows), "portal-a", "Family"))
    }
  }

  @Test fun commandTargetExactIdWinsRegardlessOfNameMatchOrder() {
    val own = player("portal-a", "Family", "Own track")
    val decoy = player("other-b", "Family", "Other track")
    for (rows in listOf(listOf(own, decoy), listOf(decoy, own))) {
      assertEquals("portal-a", target(reply(rows), "portal-a", "Family"))
    }
  }

  @Test fun metadataRenamedExactIdWinsOverStaleName() {
    val json = reply(listOf(
        player("portal-a", "Renamed room", "Own track"),
        player("other-b", "Old room", "Other track")))
    assertEquals(NowPlayingState(PlaybackState.PLAYING, title = "Own track", source = "ma"),
        metadata(json, "portal-a", "Old room"))
  }

  @Test fun commandTargetRenamedExactIdWinsOverStaleName() {
    val json = reply(listOf(
        player("portal-a", "Renamed room", "Own track"),
        player("other-b", "Old room", "Other track")))
    assertEquals("portal-a", target(json, "portal-a", "Old room"))
  }

  @Test fun metadataExactIdUsesItsGroupInsteadOfTheNameDecoysGroup() {
    val json = groupedCollision()
    assertEquals(NowPlayingState(PlaybackState.PLAYING, title = "Own group track", source = "ma"),
        metadata(json, "portal-a", "Family"))
  }

  @Test fun commandTargetExactIdUsesItsGroupInsteadOfTheNameDecoysGroup() {
    assertEquals("own-group", target(groupedCollision(), "portal-a", "Family"))
  }

  @Test fun metadataInactiveExactIdDoesNotBorrowTheNameDecoysTrack() {
    val json = reply(listOf(
        player("portal-a", "Family", "Old own track", state = "idle"),
        player("other-b", "Family", "Other track")))
    assertEquals(NowPlayingState(PlaybackState.IDLE), metadata(json, "portal-a", "Family"))
  }

  @Test fun commandTargetInactiveExactIdStillBelongsToThisPortal() {
    val json = reply(listOf(
        player("portal-a", "Family", "Old own track", state = "idle"),
        player("other-b", "Family", "Other track")))
    assertEquals("portal-a", target(json, "portal-a", "Family"))
  }

  @Test fun idOnlyMatchPreservesAllCurrentMediaFields() {
    val own = player("portal-a", "Family", "Own track")
    own.getJSONObject("current_media")
        .put("artist", "Own artist").put("album", "Own album")
        .put("image_url", "http://127.0.0.1/own-cover").put("duration", 12.5)
    val json = reply(listOf(player("other-b", "Family", "Other track"), own))
    assertEquals(NowPlayingState(PlaybackState.PLAYING, title = "Own track",
        artist = "Own artist", album = "Own album", artUrl = "http://127.0.0.1/own-cover",
        durationMs = 12_500L, source = "ma"), metadata(json, "portal-a", null))
    assertEquals("portal-a", target(json, "portal-a", null))
  }

  @Test fun nameOnlyMatchPreservesPausedMetadataAndImageFallback() {
    val own = player("portal-a", "Family", "Paused track", state = "paused")
    own.getJSONObject("current_media").put("image", "http://127.0.0.1/fallback-cover")
    val json = reply(listOf(own, player("other-b", "Other room", "Other track")))
    assertEquals(NowPlayingState(PlaybackState.PAUSED, title = "Paused track",
        artUrl = "http://127.0.0.1/fallback-cover", source = "ma"),
        metadata(json, null, "Family"))
    assertEquals("portal-a", target(json, null, "Family"))
  }

  @Test fun missingIdRetainsExistingNameFallback() {
    val json = reply(listOf(player("portal-a", "Family", "Own track")))
    assertEquals(NowPlayingState(PlaybackState.PLAYING, title = "Own track", source = "ma"),
        metadata(json, "missing-id", "Family"))
    assertEquals("portal-a", target(json, "missing-id", "Family"))
  }

  @Test fun noMatchAndAbsentIdentityReturnIdleAndNoCommandTarget() {
    val json = reply(listOf(player("other-b", "Other room", "Other track")))
    for ((id, name) in listOf("portal-a" to "Family", null to null)) {
      assertEquals(NowPlayingState(PlaybackState.IDLE), metadata(json, id, name))
      assertNull(target(json, id, name))
    }
  }

  @Test fun activeGroupRetainsPrecedenceOverSyncedTo() {
    val json = reply(listOf(
        player("active-group", "Group", "Active group track"),
        player("sync-leader", "Leader", "Synced track"),
        player("portal-a", "Family", "Own track", activeGroup = "active-group",
            syncedTo = "sync-leader")))
    assertEquals(NowPlayingState(PlaybackState.PLAYING, title = "Active group track", source = "ma"),
        metadata(json, "portal-a", "Family"))
    assertEquals("active-group", target(json, "portal-a", "Family"))
  }

  @Test fun syncedToIsUsedWhenActiveGroupIsEmpty() {
    val json = reply(listOf(
        player("sync-leader", "Leader", "Synced track"),
        player("portal-a", "Family", "Own track", syncedTo = "sync-leader")))
    assertEquals(NowPlayingState(PlaybackState.PLAYING, title = "Synced track", source = "ma"),
        metadata(json, "portal-a", "Family"))
    assertEquals("sync-leader", target(json, "portal-a", "Family"))
  }

  @Test fun duplicateNamesRetainLastNameFallbackWhenNoIdMatches() {
    val json = reply(listOf(
        player("first", "Family", "First track"),
        player("last", "Family", "Last track")))
    assertEquals(NowPlayingState(PlaybackState.PLAYING, title = "Last track", source = "ma"),
        metadata(json, "missing-id", "Family"))
    assertEquals("last", target(json, "missing-id", "Family"))
  }

  @Test fun inactiveGroupRetainsOwnTrackMetadataFallback() {
    val json = reply(listOf(
        player("own-group", "Group", "Old group track", state = "idle"),
        player("portal-a", "Family", "Own track", activeGroup = "own-group")))
    assertEquals(NowPlayingState(PlaybackState.PLAYING, title = "Own track", source = "ma"),
        metadata(json, "portal-a", "Family"))
    assertEquals("own-group", target(json, "portal-a", "Family"))
  }

  private fun groupedCollision() = reply(listOf(
      player("portal-a", "Family", "Own track", activeGroup = "own-group"),
      player("own-group", "Group", "Own group track"),
      player("other-b", "Family", "Other track", activeGroup = "other-group"),
      player("other-group", "Other group", "Other group track")))

  private fun player(
      id: String,
      name: String,
      title: String,
      state: String = "playing",
      activeGroup: String = "",
      syncedTo: String = "",
  ) = JSONObject().put("player_id", id).put("display_name", name)
      .put("state", state).put("active_group", activeGroup).put("synced_to", syncedTo)
      .put("current_media", JSONObject().put("title", title).put("artist", "")
          .put("album", "").put("image_url", "").put("duration", 0.0))

  private fun reply(players: List<JSONObject>) =
      JSONObject().put("message_id", "players/all").put("result", JSONArray(players)).toString()

  private fun metadata(json: String, id: String?, name: String?): NowPlayingState =
      androidStubs { MaControl.parseNowPlaying(json, id, name) }

  private fun target(json: String, id: String?, name: String?): String? = androidStubs {
    val cached = MaControl.javaClass.getDeclaredField("cachedPlayerId").apply { isAccessible = true }
    val original = cached.get(MaControl)
    val method = MaControl.javaClass.getDeclaredMethod(
        "resolvePlayer", MaWebSocket::class.java, String::class.java, String::class.java)
        .apply { isAccessible = true }
    val wire = Wire(json)
    val ws = MaWebSocket("127.0.0.1", 8095, socketFactory = { wire })
    val result = try {
      assertTrue("Generated WebSocket handshake must connect", ws.connect())
      method.invoke(MaControl, ws, id, name) as String?
    } finally {
      ws.close()
      if (!wire.retired) wire.close()
      cached.set(MaControl, original)
    }
    // Retire generated resources and restore the cache before any selection assertion.
    assertTrue(wire.retired)
    assertEquals(1, wire.closeCount)
    assertEquals(InetSocketAddress("127.0.0.1", 8095), wire.endpoint)
    assertEquals(6000, wire.timeout)
    val request = JSONObject(wire.clientText())
    assertEquals("players/all", request.getString("command"))
    assertEquals("players/all", request.getString("message_id"))
    assertEquals(2, request.length())
    result
  }

  private fun <T> androidStubs(block: () -> T): T =
      Mockito.mockStatic(Log::class.java).use {
        Mockito.mockStatic(Looper::class.java).use {
          Mockito.mockConstruction(Handler::class.java).use { block() }
        }
      }

  /** No super.connect call: the real protocol reads and writes generated bounded bytes. */
  private class Wire(private val reply: String) : Socket() {
    private val output = ByteArrayOutputStream()
    private var input: ByteArrayInputStream? = null
    var retired = false
      private set
    var closeCount = 0
      private set
    var endpoint: SocketAddress? = null
      private set
    var timeout = 0
      private set

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
      check(!retired && this.endpoint == null)
      this.endpoint = endpoint
      this.timeout = timeout
    }
    override fun setSoTimeout(timeout: Int) = Unit
    override fun getOutputStream() = output
    override fun getInputStream() = object : java.io.InputStream() {
      override fun read(): Int {
        check(!retired)
        if (input == null) {
          val key = output.toString("ISO-8859-1").lineSequence()
              .single { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(":").trim()
          val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
              .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
          val upgrade = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
              "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
          input = ByteArrayInputStream(upgrade.toByteArray(Charsets.ISO_8859_1) + frame(reply))
        }
        return input!!.read()
      }
    }
    override fun close() {
      closeCount++
      retired = true
      input?.close()
      output.close()
      super.close()
    }

    fun clientText(): String {
      val bytes = output.toByteArray()
      val head = output.toString("ISO-8859-1").indexOf("\r\n\r\n") + 4
      check(head >= 4 && (bytes[head].toInt() and 255) == 0x81)
      var offset = head + 2
      val second = bytes[head + 1].toInt() and 255
      check((second and 128) != 0)
      val length = when (val small = second and 127) {
        126 -> (((bytes[offset++].toInt() and 255) shl 8) or
            (bytes[offset++].toInt() and 255))
        127 -> error("Fixture request exceeded its bounded frame")
        else -> small
      }
      val mask = bytes.copyOfRange(offset, offset + 4)
      offset += 4
      check(offset + length == bytes.size)
      return ByteArray(length) { index ->
        (bytes[offset + index].toInt() xor mask[index % 4].toInt()).toByte()
      }.toString(Charsets.UTF_8)
    }

    private fun frame(text: String): ByteArray {
      val bytes = text.toByteArray(Charsets.UTF_8)
      check(bytes.size < 65536)
      return ByteArrayOutputStream().apply {
        write(0x81)
        if (bytes.size < 126) write(bytes.size)
        else { write(126); write(bytes.size ushr 8); write(bytes.size) }
        write(bytes)
      }.toByteArray()
    }
  }
}
