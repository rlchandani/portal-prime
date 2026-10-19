/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import org.json.JSONObject

/** Called only inside RemoteRoutes' existing paired-session/fleet authentication boundary. */
internal class RemoteMusicRoutes(
    context: Context,
    private val read: () -> MusicPlayerSessions.View? = { MusicPlayerSessions.read(context) },
    private val command: (JSONObject) -> MusicPlayerSessions.Result =
        { MusicPlayerSessions.command(context, it) },
) {
  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response = when (req.path) {
    "/remote/music" -> if (req.method != "GET") error(405, "method_not_allowed") else {
      val view = read()
      if (view == null) error(503, "music_request_timeout")
      else json(200, JSONObject().put("ok", true).put("music", state(view)))
    }
    "/remote/music/command" -> if (req.method != "POST") error(405, "method_not_allowed") else {
      if (req.contentLength !in 1..8_192) error(400, "bad_music_command") else {
        val body = runCatching { JSONObject(req.bodyText(8_192)) }.getOrNull()
        if (body == null) error(400, "bad_json") else {
          val result = command(body)
          val reply = JSONObject()
              .put("ok", result.status in 200..299)
              .put("admitted", result.admitted ?: JSONObject.NULL)
              .put("refreshRequired", result.admitted == null)
              .put("confirmed", false)
              .put("status", result.code)
          if (result.status !in 200..299) reply.put("error", result.code)
          result.view?.let { reply.put("music", state(it)) }
          json(result.status, reply)
        }
      }
    }
    else -> error(404, "not_found")
  }

  private fun state(view: MusicPlayerSessions.View) = MusicRemoteProtocol.state(
      view.snapshot, view.sourceRevision, view.observedAtElapsedMs)
  private fun error(status: Int, code: String) =
      json(status, JSONObject().put("ok", false).put("error", code))
  private fun json(status: Int, body: JSONObject) = FleetHttpServer.Response(status, body.toString())
}
