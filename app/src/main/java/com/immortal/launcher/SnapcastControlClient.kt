/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import org.json.JSONArray
import org.json.JSONObject

/**
 * A tiny Snapcast JSON-RPC control client (the snapserver's control port, default
 * 1705 — newline-delimited JSON-RPC 2.0, the same channel snapweb uses). It tracks
 * each stream's properties, and whenever the playing stream's metadata changes it
 * hands a [NowPlayingState] to [onState], so [MultiRoomService] can mirror it into a
 * media session. Audio is NOT handled here — the standalone Snapcast player does that;
 * this only reads "what's playing" off the server.
 *
 * Maps Music Assistant's real shape (confirmed against a live server): `artist` is an
 * array, `duration` is in seconds, `artUrl` is an absolute MA imageproxy URL.
 *
 * Original Immortal code that speaks Snapcast's protocol over a socket — it links no
 * GPL Snapcast code, so it stays in the MIT launcher. Reconnects with a fixed backoff;
 * runs on its own daemon thread.
 */
class SnapcastControlClient(
    private val host: String,
    private val port: Int,
    private val localIp: () -> String?,
    private val onConnected: (Boolean) -> Unit,
    private val onState: (NowPlayingState) -> Unit,
) {
  @Volatile private var running = false
  @Volatile private var socket: Socket? = null
  // streamId -> its "properties" object (metadata + playbackStatus).
  private val streams = HashMap<String, JSONObject>()
  // The stream THIS Portal's snapclient is consuming — resolved from the server
  // topology by matching our LAN IP to a group's client. We only ever surface this
  // stream, so a Portal whose group is idle never latches onto another group's audio.
  @Volatile private var ourStreamId: String? = null
  // This Portal's snapclient identity (same IP match) — used to find our Music Assistant
  // player when forwarding transport commands.
  @Volatile var ourClientId: String? = null
    private set
  @Volatile var ourClientName: String? = null
    private set

  fun start() {
    if (running) return
    running = true
    Thread({ loop() }, "snapcast-control").apply {
      isDaemon = true
      start()
    }
  }

  fun stop() {
    running = false
    runCatching { socket?.close() }
  }

  private fun loop() {
    while (running) {
      runCatching { connectAndRead() }
          .onFailure { Log.w(TAG, "control connection ended: ${it.message}") }
      streams.clear()
      if (running) {
        onConnected(false) // tell the UI we dropped (shows "Connecting…")
        onState(NowPlayingState(PlaybackState.IDLE)) // clear the card while disconnected
        Thread.sleep(3000) // reconnect backoff
      }
    }
  }

  private fun connectAndRead() {
    Log.i(TAG, "connecting control to $host:$port")
    val s = Socket(host, port).also { socket = it }
    val out = s.getOutputStream()
    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
    requestStatus(out)
    onConnected(true) // socket up, status requested — the UI can show "Connected"
    while (running) {
      val line = reader.readLine() ?: break
      if (line.isBlank()) continue
      runCatching { handle(JSONObject(line)) }.onFailure { Log.w(TAG, "bad message", it) }
    }
  }

  private fun requestStatus(out: OutputStream) =
      send(out, JSONObject().put("id", 1).put("jsonrpc", "2.0").put("method", "Server.GetStatus"))

  private fun send(out: OutputStream, obj: JSONObject) {
    out.write((obj.toString() + "\r\n").toByteArray(Charsets.UTF_8))
    out.flush()
  }

  private fun handle(msg: JSONObject) {
    // Full status response: rebuild the stream map and re-resolve our group's stream.
    msg.optJSONObject("result")?.optJSONObject("server")?.let { server ->
      streams.clear()
      val arr = server.optJSONArray("streams") ?: JSONArray()
      for (i in 0 until arr.length()) {
        val st = arr.getJSONObject(i)
        val id = st.optString("id")
        if (id.isNotBlank()) streams[id] = st.optJSONObject("properties") ?: JSONObject()
      }
      ourStreamId = resolveOurStream(server)
      Log.i(TAG, "our group stream = ${ourStreamId ?: "(this Portal isn't in a group)"} (ip=${localIp()})")
      emit()
      return
    }
    // Push notifications.
    when (msg.optString("method")) {
      "Stream.OnProperties" -> {
        val p = msg.optJSONObject("params") ?: return
        val id = p.optString("id")
        val props = p.optJSONObject("properties")
        if (id.isNotBlank() && props != null) {
          streams[id] = props
          emit()
        }
      }
      "Stream.OnUpdate" -> {
        val stream = msg.optJSONObject("params")?.optJSONObject("stream") ?: return
        val id = stream.optString("id")
        if (id.isNotBlank()) {
          streams[id] = stream.optJSONObject("properties") ?: JSONObject()
          emit()
        }
      }
      // Topology changed (a group's stream reassigned, a client moved/(dis)connected,
      // or a full server update) — re-resolve which stream is ours, then emit.
      "Server.OnUpdate",
      "Group.OnStreamChanged",
      "Client.OnConnect",
      "Client.OnDisconnect" ->
          runCatching { socket?.getOutputStream()?.let { requestStatus(it) } }
    }
  }

  /**
   * The stream id of the group whose clients include THIS device (matched by LAN IP),
   * or null if our snapclient isn't currently in any group (not a speaker right now).
   */
  private fun resolveOurStream(server: JSONObject): String? {
    val me = localIp() ?: return null
    val groups = server.optJSONArray("groups") ?: return null
    for (gi in 0 until groups.length()) {
      val g = groups.getJSONObject(gi)
      val clients = g.optJSONArray("clients") ?: continue
      for (ci in 0 until clients.length()) {
        val c = clients.getJSONObject(ci)
        val host = c.optJSONObject("host")
        if (normalizeIp(host?.optString("ip").orEmpty()) == me) {
          ourClientId = c.optString("id").takeIf { it.isNotBlank() }
          ourClientName = host?.optString("name")?.takeIf { it.isNotBlank() }
          return g.optString("stream_id").takeIf { it.isNotBlank() }
        }
      }
    }
    ourClientId = null
    ourClientName = null
    return null
  }

  /** Snapcast reports IPs as IPv4-mapped IPv6 (`::ffff:192.168.4.37`); take the v4 tail. */
  private fun normalizeIp(ip: String): String =
      if (ip.contains(".")) ip.substringAfterLast(":") else ip

  /** Emit the track for OUR group's stream only (playing/paused), else clear the card. */
  private fun emit() {
    val props = ourStreamId?.let { streams[it] }
    val status = props?.optString("playbackStatus")
    if (props == null || (status != "playing" && status != "paused")) {
      onState(NowPlayingState(PlaybackState.IDLE))
      return
    }
    val md = props.optJSONObject("metadata") ?: JSONObject()
    onState(
        NowPlayingState(
            state =
                if (props.optString("playbackStatus") == "paused") PlaybackState.PAUSED
                else PlaybackState.PLAYING,
            title = md.optString("title"),
            artist = joinStrings(md.opt("artist")),
            album = md.optString("album"),
            artUrl = md.optString("artUrl"),
            durationMs = (md.optDouble("duration", 0.0) * 1000).toLong(),
            source = "snapcast"))
  }

  /** Snapcast metadata fields like `artist`/`albumArtist` are string arrays. */
  private fun joinStrings(v: Any?): String =
      when (v) {
        is JSONArray -> (0 until v.length()).joinToString(", ") { v.optString(it) }
        is String -> v
        else -> ""
      }

  private companion object {
    const val TAG = "ImmortalNowPlaying"
  }
}
