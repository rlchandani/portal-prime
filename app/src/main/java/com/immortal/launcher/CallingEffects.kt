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
      is CallEffect.SendReply -> send(c, CallWire.REPLY, CallWire.encode(CallWire.Reply(effect.id, effect.to, effect.reply,
          if (effect.reply == CallReply.QUIET) ChimeConfig.load(c).quietEndMin else null)))
      is CallEffect.SendEnd -> send(c, CallWire.END, CallWire.encode(CallWire.End(effect.id, effect.to, effect.reason)))
      is CallEffect.SendKeep -> send(c, CallWire.KEEP, CallWire.encode(CallWire.Keep(effect.id, effect.to, effect.capAtMs)))
      is CallEffect.SendMediaState -> send(c, CallWire.MEDIA,
          CallWire.encode(CallWire.Media(effect.id, effect.to, effect.cameraOn, effect.micOn)))
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
    }
  }

  private fun sendRing(c: Context, call: ActiveCall) {
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

  private fun send(c: Context, type: String, body: org.json.JSONObject) {
    if (!HouseholdBus.send(c, type, body)) Log.w(TAG, "$type not sent (no household key)")
  }

  /** Incoming over anything, the photo frame included: finish the dream, wake, bring the call up. */
  private fun present(c: Context) {
    runCatching { ImmortalDreamNavigation.dismissForNavigation() }
    ScreenControl.wake(c)
    CallActivity.open(c)
  }
}
