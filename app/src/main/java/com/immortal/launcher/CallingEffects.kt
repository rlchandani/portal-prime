/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log

/** Carries out [CallEffect]s on the main thread. Each branch is one small, named action. */
internal object CallingEffects {
  private const val TAG = "HearthpaneCalling"

  fun perform(c: Context, effect: CallEffect) {
    when (effect) {
      is CallEffect.SendRing -> sendRing(c, effect.call)
      is CallEffect.SendReply -> send(c, effect.to, CallWire.REPLY, CallWire.encode(CallWire.Reply(effect.id, effect.to, effect.reply,
          if (effect.reply == CallReply.QUIET) ChimeConfig.load(c).quietEndMin else null)))
      is CallEffect.SendEnd -> send(c, effect.to, CallWire.END, CallWire.encode(CallWire.End(effect.id, effect.to, effect.reason)))
      is CallEffect.SendKeep -> send(c, effect.to, CallWire.KEEP, CallWire.encode(CallWire.Keep(effect.id, effect.to, effect.capAtMs)))
      is CallEffect.SendMediaState -> send(c, effect.to, CallWire.MEDIA,
          CallWire.encode(CallWire.Media(effect.id, effect.to, effect.cameraOn, effect.micOn)))
      is CallEffect.SendGo -> send(c, effect.to, CallPhoneWire.GO, CallPhoneWire.encodeGo(effect.id, effect.to))
      CallEffect.AutoAnswerChime -> CallRinger.chime(c)
      CallEffect.Present -> present(c)
      is CallEffect.StartRing -> if (!effect.silent) CallRinger.ring(c)
      CallEffect.StopRing -> CallRinger.stop()
      CallEffect.DropInChime -> CallRinger.chime(c)
      is CallEffect.OpenPreview -> Calling.state.value.call?.let { CallMediaHub.preview(c, it, effect.camera) }
      is CallEffect.StartMedia -> CallMediaHub.start(c, effect.call)
      is CallEffect.SetCamera -> CallMediaHub.camera(effect.on)
      is CallEffect.SetMic -> CallMediaHub.mic(effect.on)
      CallEffect.StopMedia -> CallMediaHub.stop(c)
      is CallEffect.Log -> CallingStore.log(c, effect.entry)
      CallEffect.AnnouncePresence -> Calling.announce(c, ask = false)
      is CallEffect.SendGroupRing -> sendGroupRing(c, effect)
      is CallEffect.SendJoin -> send(c, effect.to, CallWire.JOIN, CallWire.encode(CallWire.Join(effect.id, effect.to, effect.newcomer)))
      is CallEffect.StartLeg -> CallMediaHub.startLeg(c, effect.id, effect.leg)
      is CallEffect.ClosePeer -> CallMediaHub.closePeer(effect.peer)
    }
  }

  private fun sendRing(c: Context, call: ActiveCall) {
    if (call.peer.phone) {
      // A phone has no server: the ring waits in its mailbox until Portal Toolkit polls (it must be open).
      CallPhoneMailbox.post(call.peer.deviceId, CallWire.RING, CallPhoneWire.encodeRing(call.id, call.peer.deviceId, call.video,
          Calling.selfName(c)), Calling.now())
      return
    }
    // Our LAN address goes in the signed ring so the callee knows where to POST its offer.
    Thread({
      val host = HouseholdListSync.lanAddress()
      val sent = host != null && HouseholdBus.send(c, CallWire.RING, CallWire.encode(CallWire.Ring(call.id, call.peer.deviceId,
          call.kind, call.video, Calling.selfName(c), host, FleetConfig.port(c))))
      if (!sent) {
        Log.w(TAG, "ring not sent (no LAN address or household key)")
        Calling.dispatch(CallEvent.MediaFailed(call.id, CallEndReason.UNAVAILABLE, Calling.now()))
      }
    }, "hearthpane-call-ring").apply { isDaemon = true }.start()
  }

  /** Group ring: like a ring, plus each member and where it listens (so the newcomer can offer to them). */
  private fun sendGroupRing(c: Context, e: CallEffect.SendGroupRing) {
    val call = Calling.state.value.call
    val members = e.members.map { m ->
      val known = CallingStore.peers.value.firstOrNull { it.id == m.deviceId }
      val host = known?.host ?: call?.peerHost?.takeIf { call.peer.deviceId == m.deviceId }
      val port = known?.port?.takeIf { known.host != null } ?: call?.peerPort?.takeIf { call.peer.deviceId == m.deviceId } ?: 0
      CallMemberRef(m, host, port)
    }
    Thread({
      val host = HouseholdListSync.lanAddress()
      val sent = host != null && HouseholdBus.send(c, CallWire.RING, CallWire.encode(CallWire.Ring(e.id, e.to.deviceId,
          CallKind.CALL, e.video, Calling.selfName(c), host, FleetConfig.port(c), members)))
      if (!sent) Log.w(TAG, "group ring not sent (no LAN address or household key)")
    }, "hearthpane-call-ring").apply { isDaemon = true }.start()
  }

  /** Portals hear us on the signed bus; a phone collects the same message from its mailbox on its next poll. */
  private fun send(c: Context, to: String, type: String, body: org.json.JSONObject) {
    if (CallPhoneId.isPhone(to)) { CallPhoneMailbox.post(to, type, body, Calling.now()); return }
    if (!HouseholdBus.send(c, type, body)) Log.w(TAG, "$type not sent (no household key)")
  }

  /** Incoming over anything, the photo frame included: finish the dream, wake, bring the call up. */
  private fun present(c: Context) {
    runCatching { ImmortalDreamNavigation.dismissForNavigation() }
    ScreenControl.wake(c)
    CallActivity.open(c)
  }
}
