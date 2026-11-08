/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import com.immortal.launcher.youtube.YtAndroid
import java.util.TimeZone
import org.json.JSONObject

/**
 * `/remote/musicshare` for the paired phone (session auth, body limit and JSON parsing are done
 * upstream by [RemoteCompanionRoutes]).
 * - GET → `{ok, state: idle|playing|quiet|routine, nowPlaying:{title,artist}|null, upNext, signedIn,
 *   quietUntil, lastShare:{shareId, count, skipped}|null}`; the phone's room list and confirmation.
 * - POST `{kind, id, listId?, action, shuffle, from, title?, artist?, thumb?, playAnyway?, shareId?}`
 *   → `{ok, status: playing|queued|quiet|declined, action, interrupted?, before?, ahead?, count?, reason?}`.
 * Quiet hours answer `quiet` unless `playAnyway`; a routine on screen or a call answers `declined`.
 * Guest mode hides the library but playback is allowed, so it doesn't refuse shares.
 * Playlists on a Portal with no account signed in open on the visible Music player, so Play next /
 * Add to queue over a playing song there answer `declined` ("Sign in … to queue playlists").
 */
internal object MusicShareRoute : RemoteFeatureRoutes.Route {
  const val PATH = "/remote/musicshare"

  override fun handle(context: Context, method: String, body: JSONObject): FleetHttpServer.Response = when (method) {
    "GET" -> RemoteFeatureRoutes.json(200, status(context))
    "POST" -> post(context, body)
    else -> RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
  }

  private data class Room(val quiet: Boolean, val routine: Boolean, val inCall: Boolean, val quietUntil: String)

  private fun room(context: Context): Room {
    val chime = ChimeConfig.load(context)
    val quiet = DinnerQuiet.isQuiet(chime, System.currentTimeMillis(), TimeZone.getDefault())
    return Room(quiet, RoutineScreenState.ownsScreen(), RoutineScheduler.busy(context), MusicSharePolicy.clock(chime.quietEndMin))
  }

  private fun status(context: Context): JSONObject {
    val room = room(context)
    val engine = YouTubeMusicEngine.state.value
    val current = engine.queue.current?.takeIf { engine.isPlaying }
    val nowPlaying = current?.let { JSONObject().put("title", it.track.title).put("artist", it.track.artist) }
    val upNext = if (engine.queue.isEmpty) 0 else engine.queue.entries.size - engine.queue.currentIndex - 1
    return RemoteFeatureRoutes.ok()
        .put("state", MusicSharePolicy.roomState(room.quiet, room.routine, room.inCall, current != null))
        .put("nowPlaying", nowPlaying ?: JSONObject.NULL)
        .put("upNext", upNext)
        .put("signedIn", YtAndroid.library(context).activeAccount.value != null)
        .put("quietUntil", if (room.quiet) room.quietUntil else JSONObject.NULL)
        .put("lastShare", lastShare(engine.queue) ?: JSONObject.NULL)
  }

  private fun lastShare(queue: YtQueue): JSONObject? {
    val share = MusicShare.lastShare() ?: return null
    val skipped = share.failure ?: MusicShare.skippedReason(share, queue)
    return JSONObject().put("shareId", share.shareId ?: JSONObject.NULL)
        .put("count", share.count ?: JSONObject.NULL).put("skipped", skipped ?: JSONObject.NULL)
  }

  private fun post(context: Context, body: JSONObject): FleetHttpServer.Response {
    val request = when (val parsed = MusicShareRequests.parse(body)) {
      is MusicShareRequests.Parsed.Invalid -> return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error(parsed.code))
      is MusicShareRequests.Parsed.Ok -> parsed.request
    }
    val room = room(context)
    val decision = MusicSharePolicy.decide(room.quiet, room.routine, room.inCall, request.playAnyway)
    val reply = when (decision) {
      MusicSharePolicy.Decision.QUIET -> RemoteFeatureRoutes.ok().put("status", "quiet").put("quietUntil", room.quietUntil)
      MusicSharePolicy.Decision.ROUTINE, MusicSharePolicy.Decision.CALL -> RemoteFeatureRoutes.ok().put("status", "declined")
          .put("reason", MusicSharePolicy.reason(decision, DinnerCall.selfName(context)))
      MusicSharePolicy.Decision.ACCEPT, MusicSharePolicy.Decision.ACCEPT_QUIETLY ->
        return accept(context, request, decision == MusicSharePolicy.Decision.ACCEPT_QUIETLY)
    }
    return RemoteFeatureRoutes.json(200, reply)
  }

  private fun accept(context: Context, request: MusicShareRequest, quietly: Boolean): FleetHttpServer.Response {
    if (request.kind == MusicShareKind.PLAYLIST) {
      val engine = YouTubeMusicEngine.state.value
      val current = engine.queue.current?.takeIf { engine.isPlaying }
      val effective = if (current != null) request.action else MusicShareAction.NOW
      val signedIn = YtAndroid.library(context).activeAccount.value != null
      val source = MusicSharePolicy.playlistSource(signedIn, current != null, request.action)
      if (source == MusicSharePolicy.PlaylistSource.NEEDS_SIGN_IN) {
        return RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok().put("status", "declined")
            .put("reason", MusicSharePolicy.signInReason(DinnerCall.selfName(context))))
      }
      MusicShare.sharePlaylist(context, request, quietly, source)
      return RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok().put("status", statusOf(effective))
          .put("action", effective.wire).put("quietly", quietly)
          .put("interrupted", if (effective == MusicShareAction.NOW) current?.track?.title ?: JSONObject.NULL else JSONObject.NULL)
          .put("before", current?.track?.title ?: JSONObject.NULL)
          .put("ahead", if (current == null) 0 else engine.queue.entries.size - engine.queue.currentIndex - 1))
    }
    val outcome = MusicShare.shareVideo(context, request, quietly)
        ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("player_busy"))
    return RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok().put("status", statusOf(outcome.effective))
        .put("action", outcome.effective.wire).put("quietly", quietly).put("count", 1)
        .put("interrupted", outcome.interrupted ?: JSONObject.NULL)
        .put("before", outcome.before ?: JSONObject.NULL)
        .put("ahead", outcome.ahead))
  }

  private fun statusOf(action: MusicShareAction): String = if (action == MusicShareAction.NOW) "playing" else "queued"
}
