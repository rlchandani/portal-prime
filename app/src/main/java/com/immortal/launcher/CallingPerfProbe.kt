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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `/perf/calling` (fleet token, measurement builds only: BuildConfig.PORTAL_CALL_LOOPBACK). Lets a
 * night-time device test drive calls between home Portals without touching the screens: state and
 * per-member stats (never media), test frames instead of the camera, dial / accept / add / mute /
 * hang up, and the in-process 3rd-member load probe. Inert (403) in every normal build.
 */
internal object CallPerfProbe {
  private const val WAIT_MS = 3_000L
  private val main = Handler(Looper.getMainLooper())

  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (!BuildConfig.PORTAL_CALL_LOOPBACK) return json(403, JSONObject().put("ok", false).put("error", "calling_probe_disabled"))
    if (req.method == "GET") return json(200, state(context))
    if (req.method != "POST") return json(405, JSONObject().put("ok", false).put("error", "method_not_allowed"))
    val action = req.queryParam("action") ?: return json(400, JSONObject().put("ok", false).put("error", "action_required"))
    val peer = req.queryParam("peer")
    val on = req.queryParam("on") == "1"
    val ok = onMain {
      when (action) {
        "frames" -> { CallTestMode.frames = on; true }
        "dial" -> peer != null && Calling.dial(context, peer, CallKind.CALL, video = req.queryParam("video") != "0")
        "accept" -> { Calling.accept(audioOnly = false); true }
        "add" -> peer != null && Calling.addPerson(context, peer)
        "hangup" -> { Calling.hangUp(); true }
        "dismiss" -> { Calling.dismissEnded(); true }
        "mic" -> Calling.state.value.call?.let { if (it.micOn != on) Calling.toggleMic(); true } ?: false
        "camera" -> Calling.state.value.call?.let { if (it.cameraOn != on) Calling.toggleCamera(); true } ?: false
        "announce" -> { Calling.announce(context.applicationContext, ask = true); true }
        "probe" -> CallMediaHub.probe(on)
        // Night tests: "visual" rings silently in quiet hours (no sound by policy); restore "block" after.
        "quietMode" -> req.queryParam("v")?.let { com.immortal.launcher.settings.CallingSettings.set(context, "quietMode", it) } ?: false
        else -> false
      }
    }
    return json(if (ok) 200 else 409, JSONObject().put("ok", ok).put("action", action).put("state", state(context)))
  }

  private fun state(context: Context): JSONObject {
    val s = Calling.state.value
    val call = s.call
    val out = JSONObject().put("ok", true).put("testFrames", CallTestMode.frames).put("media", CallMediaHub.media.value != null)
        .put("probe", CallMediaHub.probeOn.value)
    call?.let { c ->
      out.put("call", JSONObject().put("id", c.id).put("phase", c.phase.name).put("direction", c.direction.name)
          .put("peer", c.peer.name).put("people", c.people).put("cameraOn", c.cameraOn).put("micOn", c.micOn)
          .put("legs", JSONArray(c.legs.map { JSONObject().put("name", it.peer.name).put("phase", it.phase.name) }))
          .put("notice", c.notice?.let { CallAddPeople.notice(it) }))
    }
    s.ended?.let { out.put("ended", JSONObject().put("peer", it.peer.name).put("reason", it.reason.name)) }
    out.put("stats", JSONObject().apply {
      CallMediaHub.stats.value.forEach { (peer, st) ->
        put(peer.take(PEER_TAG), JSONObject().put("send", "${st.sendWidth}x${st.sendHeight}@${"%.1f".format(st.sendFps)}")
            .put("sendKbps", st.sendKbps).put("recvFps", st.recvFps).put("recvKbps", st.recvKbps).put("encoder", st.encoder)
            .put("codec", st.codec).put("limit", st.limitedBy).put("rttMs", st.rttMs ?: -1))
      }
    })
    out.put("rooms", JSONArray(CallingStore.peers.value.map {
      JSONObject().put("id", it.id).put("name", it.name).put("presence", CallPolicy.presence(it, Calling.now()).name)
          .put("addressKnown", it.host != null)
    }))
    out.put("phones", JSONArray(CallPhoneMailbox.phones.value.map { JSONObject().put("name", it.name).put("open", it.reachable(Calling.now())) }))
    out.put("self", Calling.selfName(context))
    return out
  }

  private fun onMain(block: () -> Boolean): Boolean {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val latch = CountDownLatch(1)
    var result = false
    main.post { result = runCatching(block).getOrDefault(false); latch.countDown() }
    latch.await(WAIT_MS, TimeUnit.MILLISECONDS)
    return result
  }

  private fun json(status: Int, body: JSONObject) = FleetHttpServer.Response(status, body.toString())
  private const val PEER_TAG = 8
}
