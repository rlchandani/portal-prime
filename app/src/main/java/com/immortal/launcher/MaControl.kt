/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Sends transport commands (play/pause/next/previous) to Music Assistant's WebSocket API
 * for THIS Portal's player. The now-playing card's controls land here (via
 * [MultiRoomService]) because MA's Snapcast stream is `canPlay=false` — the snapserver
 * can't drive playback, only MA's own API can.
 *
 * Every socket requires the configured Music Assistant API token and an explicit correlated
 * authenticated:true reply. Legacy username/password login is unsupported until its pinned
 * response contract is verified; no minted token or auth-success cache crosses endpoints.
 * Provider credentials (including Google cookies) remain on Music Assistant. The raw LAN
 * WebSocket transport requires a trusted network and rejects HTTPS/WSS URLs.
 * The player is still resolved from players/all by Snapcast client id/name and routed to its
 * sync group. The MA API port is configurable ([ImmortalSettings.maPort], default 8095).
 */
object MaControl {
  private const val TAG = "ImmortalNowPlaying"

  private val authentication = MaLegacyAuth()
  @Volatile private var cachedPlayerId: String? = null
  private val exec = Executors.newSingleThreadExecutor()
  private val main = Handler(Looper.getMainLooper())

  private fun authStatus(s: String) = main.post { MultiRoomStatus.maAuth = s }

  /**
   * Test the configured API token. Reachability or a login-shaped reply alone cannot start
   * the relay; authorization must explicitly confirm authenticated:true.
   */
  fun testLogin(context: Context) {
    val host = ImmortalSettings.snapcastHost(context)
    val port = ImmortalSettings.maPort(context)
    val apiToken = ImmortalSettings.maApiToken(context)
    val user = ImmortalSettings.maUser(context)
    val pass = ImmortalSettings.maPass(context)
    if (host.isBlank()) {
      authStatus("Set the server address first")
      return
    }
    authentication.configurationFailure(apiToken, user, pass)?.let {
      authStatus(it.problem)
      return
    }
    authStatus("Authenticating…")
    exec.execute {
      val ws = MaWebSocket(host, port)
      try {
        if (!ws.connect()) {
          authStatus("Can't reach Music Assistant")
          return@execute
        }
        ws.readText() // server hello is not authorization
        if (authorize(ws, apiToken, user, pass)) {
          authStatus("Authenticated with Music Assistant ✓")
          main.post { MultiRoomService.sync(context.applicationContext) }
        }
      } catch (_: Exception) {
        authStatus("Can't reach Music Assistant")
      } finally {
        ws.close()
      }
    }
  }

  /**
   * What OUR player is currently playing, read from MA's WebSocket API (the same source MA's
   * own UI shows). This is the fill-in for sources whose metadata never reaches the Snapcast
   * stream — notably an AirPlay receiver bridged to the group, where snapserver sees a bare
   * stream with no metadata at all. Returns a PLAYING/PAUSED state with the track, or IDLE
   * when our player isn't actively playing / MA is unreachable / no credentials are set.
   *
   * Library and radio content MA plays already carries metadata in the Snapcast stream
   * ([SnapcastControlClient] reads it without credentials); this covers the streams that don't.
   * Blocking — call off the main thread.
   */
  fun nowPlaying(context: Context, clientId: String?, clientName: String?): NowPlayingState {
    val idle = NowPlayingState(PlaybackState.IDLE)
    val host = ImmortalSettings.snapcastHost(context)
    val port = ImmortalSettings.maPort(context)
    val user = ImmortalSettings.maUser(context)
    val pass = ImmortalSettings.maPass(context)
    val apiToken = ImmortalSettings.maApiToken(context)
    if (host.isBlank()) return idle
    authentication.configurationFailure(apiToken, user, pass)?.let {
      authStatus(it.problem)
      return idle
    }
    val ws = MaWebSocket(host, port)
    if (!runCatching { ws.connect() }.getOrDefault(false)) return idle
    return try {
      ws.readText() // server hello
      if (!authorize(ws, apiToken, user, pass)) return idle
      send(ws, "players/all", null)
      val r = await(ws, "players/all") ?: return idle
      parseNowPlaying(r, clientId, clientName)
    } catch (_: Exception) {
      Log.w(TAG, "MA now-playing failed")
      idle
    } finally {
      ws.close()
    }
  }

  /** Pick our player out of `players/all` and read its current track (its sync leader's, if
   *  grouped). Extracted from the socket so the field mapping is unit-testable. */
  internal fun parseNowPlaying(playersAllJson: String, clientId: String?, clientName: String?): NowPlayingState {
    val idle = NowPlayingState(PlaybackState.IDLE)
    val arr = JSONObject(playersAllJson).optJSONArray("result") ?: return idle
    val byId = HashMap<String, JSONObject>()
    var ours: JSONObject? = null
    for (i in 0 until arr.length()) {
      val p = arr.getJSONObject(i)
      byId[p.optString("player_id")] = p
      val pid = p.optString("player_id")
      val name = p.optString("display_name")
      if ((clientId != null && pid == clientId) || (clientName != null && name == clientName)) ours = p
    }
    val me = ours ?: return idle
    // When synced, the queue + current track live on the group / leader player.
    val leaderId =
        me.optString("active_group").ifBlank { me.optString("synced_to") }.ifBlank { me.optString("player_id") }
    val leader = byId[leaderId] ?: me
    return stateOf(leader) ?: stateOf(me) ?: idle
  }

  /** Build a now-playing state from a player's `current_media` + `state`, or null if it isn't
   *  actively playing / has no track. */
  private fun stateOf(p: JSONObject): NowPlayingState? {
    val ps =
        when (p.optString("state").lowercase()) {
          "playing" -> PlaybackState.PLAYING
          "paused" -> PlaybackState.PAUSED
          else -> return null // idle / off — not surfaced
        }
    val cm = p.optJSONObject("current_media") ?: return null
    val title = cm.optString("title").takeIf { it.isNotBlank() } ?: return null
    return NowPlayingState(
        state = ps,
        title = title,
        artist = cm.optString("artist"),
        album = cm.optString("album"),
        artUrl = cm.optString("image_url").ifBlank { cm.optString("image") },
        durationMs = (cm.optDouble("duration", 0.0) * 1000).toLong(),
        source = "ma")
  }

  /** Map a media-session transport action to MA, off the main thread. */
  fun command(context: Context, cmd: String, clientId: String?, clientName: String?) {
    val host = ImmortalSettings.snapcastHost(context)
    val port = ImmortalSettings.maPort(context)
    val user = ImmortalSettings.maUser(context)
    val pass = ImmortalSettings.maPass(context)
    val apiToken = ImmortalSettings.maApiToken(context)
    if (host.isBlank()) {
      Log.w(TAG, "MA control: not configured (need a server address)")
      return
    }
    authentication.configurationFailure(apiToken, user, pass)?.let {
      authStatus(it.problem)
      return
    }
    exec.execute {
      runCatching { send(host, port, apiToken, user, pass, cmd, clientId, clientName) }
          .onFailure { Log.w(TAG, "MA command failed") }
    }
  }

  private fun send(
      host: String,
      port: Int,
      apiToken: String,
      user: String,
      pass: String,
      cmd: String,
      clientId: String?,
      clientName: String?,
  ) {
    val ws = MaWebSocket(host, port)
    if (!ws.connect()) {
      Log.w(TAG, "MA WS connect failed")
      return
    }
    try {
      ws.readText() // server hello
      if (!authorize(ws, apiToken, user, pass)) return
      val target = resolvePlayer(ws, clientId, clientName) ?: return
      send(ws, "players/cmd/$cmd", JSONObject().put("player_id", target))
      val ack = await(ws, "players/cmd/$cmd")
      val acknowledged = ack?.let {
        MaMusicProtocol.parseCommandReply(it, "players/cmd/$cmd") is MaMusicProtocol.Reply.Success
      } == true
      Log.i(TAG, if (acknowledged) "MA transport acknowledged" else "MA transport not acknowledged")
    } finally {
      ws.close()
    }
  }

  /** Authorize this socket using the configured API token and the strict pure auth helper. */
  private fun authorize(ws: MaWebSocket, apiToken: String, user: String, pass: String): Boolean {
    return when (val reply = authentication.authorize(apiToken, user, pass) { request ->
      ws.sendText(request.toString())
      await(ws, request.getString("message_id"))
    }) {
      is MaMusicProtocol.Reply.Success -> true
      is MaMusicProtocol.Reply.Failure -> {
        authStatus(reply.problem)
        false
      }
    }
  }

  /** Find our player via players/all and return the id to command (its group if synced). */
  private fun resolvePlayer(ws: MaWebSocket, clientId: String?, clientName: String?): String? {
    send(ws, "players/all", null)
    val r = await(ws, "players/all") ?: return null
    val arr = JSONObject(r).optJSONArray("result") ?: return null
    var ours: JSONObject? = null
    for (i in 0 until arr.length()) {
      val p = arr.getJSONObject(i)
      val pid = p.optString("player_id")
      val name = p.optString("display_name")
      if ((clientId != null && pid == clientId) || (clientName != null && name == clientName)) ours = p
    }
    if (ours == null) {
      Log.w(TAG, "MA: no player matched our snapclient")
      return null
    }
    // Command the sync group / leader if this player is part of one, else itself.
    val target =
        ours.optString("active_group").takeIf { it.isNotBlank() }
            ?: ours.optString("synced_to").takeIf { it.isNotBlank() }
            ?: ours.optString("player_id")
    cachedPlayerId = target
    return target
  }

  private fun send(ws: MaWebSocket, command: String, args: JSONObject?) {
    val o = JSONObject().put("command", command).put("message_id", command)
    if (args != null) o.put("args", args)
    ws.sendText(o.toString())
  }

  /** Correlate the reply: auth uses unique IDs; legacy player requests use the command name. */
  private fun await(ws: MaWebSocket, messageId: String): String? {
    repeat(60) {
      val t = ws.readText() ?: return null
      if (runCatching { JSONObject(t).optString("message_id") }.getOrNull() == messageId) return t
    }
    return null
  }
}
