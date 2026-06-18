/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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
 * MA 2.9 requires auth: we `auth/login` with the user's MA username/password to get a
 * token, then `auth {token}` to authorize the socket, then `players/cmd/<cmd>`. The token
 * is cached in memory; on failure we re-login. The player to target is resolved from
 * `players/all` by matching our Snapcast client id/name, then routed to its sync group if
 * it's part of one.
 */
object MaControl {
  private const val TAG = "ImmortalNowPlaying"
  private const val MA_PORT = 8096

  @Volatile private var token: String? = null
  @Volatile private var cachedPlayerId: String? = null
  private val exec = Executors.newSingleThreadExecutor()
  private val main = Handler(Looper.getMainLooper())

  private fun authStatus(s: String) = main.post { MultiRoomStatus.maAuth = s }

  /** Test the MA credentials and report the result to the settings screen. */
  fun testLogin(context: Context) {
    val host = ImmortalSettings.snapcastHost(context)
    val user = ImmortalSettings.maUser(context)
    val pass = ImmortalSettings.maPass(context)
    if (host.isBlank()) {
      authStatus("Set the server address first")
      return
    }
    if (user.isBlank() || pass.isBlank()) {
      authStatus("Enter your Music Assistant username and password")
      return
    }
    authStatus("Signing in…")
    exec.execute {
      val ws = MaWebSocket(host, MA_PORT)
      if (!runCatching { ws.connect() }.getOrDefault(false)) {
        authStatus("Can't reach Music Assistant at $host")
        return@execute
      }
      try {
        ws.readText()
        token = null // force a fresh login so we actually test the typed credentials
        authStatus(if (authorize(ws, user, pass)) "Signed in to Music Assistant ✓" else "Invalid username or password")
      } finally {
        ws.close()
      }
    }
  }

  /** Map a media-session transport action to MA, off the main thread. */
  fun command(context: Context, cmd: String, clientId: String?, clientName: String?) {
    val host = ImmortalSettings.snapcastHost(context)
    val user = ImmortalSettings.maUser(context)
    val pass = ImmortalSettings.maPass(context)
    if (host.isBlank() || user.isBlank()) {
      Log.w(TAG, "MA control: not configured (need server + MA username/password)")
      return
    }
    exec.execute {
      runCatching { send(host, user, pass, cmd, clientId, clientName) }
          .onFailure { Log.w(TAG, "MA command '$cmd' failed: ${it.message}") }
    }
  }

  private fun send(
      host: String,
      user: String,
      pass: String,
      cmd: String,
      clientId: String?,
      clientName: String?,
  ) {
    val ws = MaWebSocket(host, MA_PORT)
    if (!ws.connect()) {
      Log.w(TAG, "MA WS connect failed ($host:$MA_PORT)")
      return
    }
    try {
      ws.readText() // server hello
      if (!authorize(ws, user, pass)) return
      val target = resolvePlayer(ws, clientId, clientName) ?: return
      send(ws, "players/cmd/$cmd", JSONObject().put("player_id", target))
      val ack = await(ws, "players/cmd/$cmd")
      Log.i(TAG, "MA '$cmd' → player=$target ack=${ack?.take(160)}")
    } finally {
      ws.close()
    }
  }

  /** Authorize the socket: reuse a cached token, else log in to mint one. */
  private fun authorize(ws: MaWebSocket, user: String, pass: String): Boolean {
    token?.let { t ->
      send(ws, "auth", JSONObject().put("token", t).put("device_name", DEVICE))
      val r = await(ws, "auth")
      if (r != null && !r.contains("error_code")) return true
      Log.i(TAG, "MA token rejected, re-logging in")
      token = null
    }
    send(ws, "auth/login", JSONObject().put("username", user).put("password", pass).put("device_name", DEVICE))
    val r = await(ws, "auth/login")
    if (r == null) {
      Log.w(TAG, "MA login: no response")
      return false
    }
    val result = JSONObject(r).optJSONObject("result")
    if (result?.optBoolean("success", false) != true) {
      val err = result?.optString("error").orEmpty()
      Log.w(TAG, "MA login rejected: ${r.take(200)}")
      authStatus(err.ifBlank { "Invalid username or password" })
      return false
    }
    // The server returns the JWT as `access_token` (older builds used `token`).
    val tok =
        result.optString("access_token").takeIf { it.isNotBlank() }
            ?: result.optString("token").takeIf { it.isNotBlank() }
    if (tok == null) {
      Log.w(TAG, "MA login ok but no token field: ${r.take(120)}")
      authStatus("Signed in, but no token received")
      return false
    }
    token = tok
    // The app follows login with an explicit authorize; mirror it so the socket is live.
    send(ws, "auth", JSONObject().put("token", tok).put("device_name", DEVICE))
    await(ws, "auth")
    Log.i(TAG, "MA authorized")
    authStatus("Signed in to Music Assistant ✓")
    return true
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
      Log.i(
          TAG,
          "MA player: id=$pid name='$name' prov=${p.optString("provider")} state=${p.optString("state")} synced_to=${p.optString("synced_to")} active_group=${p.optString("active_group")}")
      if ((clientId != null && pid == clientId) || (clientName != null && name == clientName)) ours = p
    }
    if (ours == null) {
      Log.w(TAG, "MA: no player matched our snapclient (id=$clientId name=$clientName)")
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

  /** Read messages until one matches [messageId] (our message_ids equal the command). */
  private fun await(ws: MaWebSocket, messageId: String): String? {
    repeat(60) {
      val t = ws.readText() ?: return null
      if (runCatching { JSONObject(t).optString("message_id") }.getOrNull() == messageId) return t
    }
    return null
  }

  private const val DEVICE = "Immortal"
}
