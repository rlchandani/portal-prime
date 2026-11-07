/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Share to Portal, Portal side. INTEGRATOR: call [install] once from `ImmortalApp.onCreate`.
 *
 * - `GET  /remote/share` → `{ok, sharing, recent:[{id,state}]}` (phone reads dismissed/cleared/canceled)
 * - `POST /remote/share` with `type` note | link | photo | retract (validated by [SharePayloads])
 * - Bus `share_note` `{id, op:"gone"|"back", why}`: one note sent to both rooms is one note, so
 *   dismissing (or undoing) on either Portal mirrors to the other.
 */
internal object ShareFeature {
  private const val TAG = "ImmortalShare"
  const val ROUTE = "/remote/share"
  const val BUS_NOTE = "share_note"
  private val main = Handler(Looper.getMainLooper())

  fun install(context: Context) {
    val app = context.applicationContext
    RemoteFeatureRoutes.register(ROUTE) { ctx, method, body -> route(ctx, method, body) }
    HouseholdBus.register(BUS_NOTE) { ctx, message -> main.post { onPeerNote(ctx, message.body) } }
    main.post { HomeNoteStore.clearIfDue(app) }
  }

  private fun route(context: Context, method: String, body: JSONObject): FleetHttpServer.Response {
    val settings = ShareConfig.load(context)
    if (method == "GET") return RemoteFeatureRoutes.json(200, status(context, settings.allowSharing))
    if (method != "POST") return RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
    val request = SharePayloads.parse(body) ?: return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("invalid_share"))
    if (!settings.allowSharing && request !is ShareRequest.Retract)
      return RemoteFeatureRoutes.json(403, RemoteFeatureRoutes.error("sharing_off"))
    return when (request) {
      is ShareRequest.Note -> receiveNote(context, request)
      is ShareRequest.Link -> receiveLink(context, request)
      is ShareRequest.Photo -> receivePhoto(context, request)
      is ShareRequest.Retract -> {
        HomeNoteStore.retract(context, request.id)
        RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok().put("id", request.id)
            .put("state", HomeNoteStore.current(context).stateOf(request.id) ?: JSONObject.NULL))
      }
    }
  }

  private fun status(context: Context, sharing: Boolean): JSONObject {
    HomeNoteStore.clearIfDue(context)
    val recent = JSONArray(HomeNoteStore.current(context).recent().take(24).map { (id, state) ->
      JSONObject().put("id", id).put("state", state) })
    return RemoteFeatureRoutes.ok().put("sharing", sharing).put("recent", recent)
  }

  private fun accepted(id: String, state: String) =
      RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok().put("id", id).put("state", state))

  private fun receiveNote(context: Context, request: ShareRequest.Note): FleetHttpServer.Response {
    val now = HomeNoteStore.clock()
    val before = HomeNoteStore.current(context).stateOf(request.id)
    HomeNoteStore.add(context, HomeNote(request.id, request.from, now, text = request.text))
    if (before == null) main.post { chimeIfAllowed(context) }
    return accepted(request.id, HomeNoteStore.current(context).stateOf(request.id) ?: HomeNotes.SHOWN)
  }

  /** Quiet hours: a link becomes a note with an Open button instead of opening. */
  private fun receiveLink(context: Context, request: ShareRequest.Link): FleetHttpServer.Response {
    val known = HomeNoteStore.current(context).stateOf(request.id)
    if (known != null) return accepted(request.id, known)
    if (ShareQuiet.isQuietNow(context, HomeNoteStore.clock())) {
      HomeNoteStore.add(context, HomeNote(request.id, request.from, HomeNoteStore.clock(), url = request.url, title = request.title))
      HomeNoteStore.link(context, request.id, "note")
      return accepted(request.id, "note")
    }
    HomeNoteStore.link(context, request.id, "opening")
    main.post { SharedLinkActivity.show(context, request) }
    return accepted(request.id, "opening")
  }

  /** Marks an upload the phone already had acknowledged; checks the file really is that upload. */
  private fun receivePhoto(context: Context, request: ShareRequest.Photo): FleetHttpServer.Response {
    val file = File(request.path)
    if (!file.isFile || file.length() != request.bytes) return RemoteFeatureRoutes.json(404, RemoteFeatureRoutes.error("no_such_photo"))
    FreshPhotoStore.add(context, FreshPhoto(request.id, file.path, request.from, FreshPhotoStore.clock()))
    return accepted(request.id, "fresh")
  }

  private fun chimeIfAllowed(context: Context) {
    if (!ShareConfig.load(context).noteChime || ShareQuiet.isQuietNow(context, HomeNoteStore.clock())) return
    HomeNoteStore.chimedAt.value = HomeNoteStore.clock()
    runCatching { ChimePlayer.playChime(context) }.onFailure { Log.w(TAG, "note chime failed", it) }
  }

  // ----- Home actions (called by the note composables) -----------------------------------------

  fun dismiss(context: Context, id: String) {
    HomeNoteStore.dismiss(context, id)
    HouseholdBus.send(context, BUS_NOTE, JSONObject().put("id", id).put("op", "gone").put("why", HomeNotes.DISMISSED))
  }

  fun undo(context: Context, id: String) {
    HomeNoteStore.undo(context, id)
    HouseholdBus.send(context, BUS_NOTE, JSONObject().put("id", id).put("op", "back"))
  }

  fun linkFinished(context: Context, id: String, opened: Boolean) =
      HomeNoteStore.link(context, id, if (opened) "opened" else "canceled")

  fun openLink(context: Context, url: String) {
    if (!SharePayloads.validUrl(url)) return
    runCatching {
      context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
          .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Log.w(TAG, "no browser for shared link", it) }
  }

  /** Peer Portal dismissed or restored a note; validated like any untrusted input. */
  private fun onPeerNote(context: Context, body: JSONObject) {
    val id = (body.opt("id") as? String)?.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{1,64}$")) } ?: return
    when (body.opt("op")) {
      "gone" -> HomeNoteStore.dismiss(context, id)
      "back" -> HomeNoteStore.undo(context, id)
    }
  }
}
