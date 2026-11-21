/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/** Where "Pair this Portal with another Portal" is. */
internal enum class PortalPinStage { LIST, SHOW, TYPE, JOINING, VERIFY, REPAIR, DONE, FAILED }

/** Which side of the pinning this Portal is on once the pictures show. */
internal enum class PortalPinSide { SHOWED_CODE, TYPED_CODE }

/**
 * The pairing screens' state. Immutable: every step returns a new state; the caller owns the
 * protocol objects (inviter session, joiner result) and storage. Never holds a secret beyond the
 * code on screen. Pure.
 */
@Immutable
internal data class PortalPinFlowState(
    val stage: PortalPinStage = PortalPinStage.LIST,
    val invite: HomeLinkInvite? = null,
    val entry: HomeLinkCodeEntry = HomeLinkCodeEntry(domain = HomeLinkDomain.PORTAL_PIN),
    val side: PortalPinSide? = null,
    val peer: HomeLinkPeer? = null,
    val safety: HomeLinkSafetyNumber? = null,
    val failure: HomeLinkFailure = HomeLinkFailure.MISMATCH,
    /** A line under the keypad ("Nothing answered that code…"), or null. */
    val note: String? = null,
    /** The pin (kid) the person asked to remove, waiting for the confirm. */
    val removing: String? = null,
) {
  fun showCode(invite: HomeLinkInvite): PortalPinFlowState =
      copy(stage = PortalPinStage.SHOW, invite = invite, side = PortalPinSide.SHOWED_CODE, peer = null, safety = null, note = null)

  fun typeCode(): PortalPinFlowState = copy(stage = PortalPinStage.TYPE, invite = null, side = PortalPinSide.TYPED_CODE,
      entry = HomeLinkCodeEntry(domain = HomeLinkDomain.PORTAL_PIN), peer = null, safety = null, note = null)

  fun typed(entry: HomeLinkCodeEntry): PortalPinFlowState = copy(entry = entry, note = null)

  /** Join pressed with a complete code: the network round runs. */
  fun joining(): PortalPinFlowState = if (stage == PortalPinStage.TYPE && entry.code != null) copy(stage = PortalPinStage.JOINING) else this

  /** What the typing side's LAN round gave. */
  fun joined(result: PortalPinJoin.Result): PortalPinFlowState = when (result) {
    is PortalPinJoin.Result.Comparing -> comparing(result.peer, result.safety)
    PortalPinJoin.Result.NotFound -> copy(stage = PortalPinStage.TYPE, note = PortalPinCopy.NOT_FOUND)
    PortalPinJoin.Result.NoAnswer -> copy(stage = PortalPinStage.TYPE, note = PortalPinCopy.NO_ANSWER)
    PortalPinJoin.Result.Tampered -> failed(HomeLinkFailure.TAMPERED)
  }

  /** The showing side's inviter moved on (polled while the code is up). */
  fun inviter(state: HomeLinkInviterState): PortalPinFlowState = when {
    stage != PortalPinStage.SHOW -> this
    state is HomeLinkInviterState.Comparing -> comparing(state.peer, state.safety)
    state is HomeLinkInviterState.Failed -> failed(state.reason)
    else -> this
  }

  fun comparing(peer: HomeLinkPeer, safety: HomeLinkSafetyNumber): PortalPinFlowState =
      copy(stage = PortalPinStage.VERIFY, peer = peer, safety = safety, note = null)

  /** After "They match": what the roster did with the pin. */
  fun pinned(outcome: PortalPinOutcome): PortalPinFlowState = when (outcome) {
    PortalPinOutcome.PINNED, PortalPinOutcome.ALREADY_PINNED -> copy(stage = PortalPinStage.DONE)
    PortalPinOutcome.NEEDS_REPAIR -> copy(stage = PortalPinStage.REPAIR)
    PortalPinOutcome.REFUSED, PortalPinOutcome.NOT_STORED -> failed(HomeLinkFailure.TAMPERED)
  }

  fun mismatch(): PortalPinFlowState = failed(HomeLinkFailure.MISMATCH)

  fun failed(reason: HomeLinkFailure): PortalPinFlowState =
      copy(stage = PortalPinStage.FAILED, failure = reason, peer = null, safety = null, invite = null)

  fun askRemove(kid: String): PortalPinFlowState = copy(removing = kid)

  fun cancelRemove(): PortalPinFlowState = copy(removing = null)

  /** Back to the list; the caller ends any live invite. */
  fun list(): PortalPinFlowState = PortalPinFlowState()

  /**
   * The system Back key: closes an open remove confirm, else any pairing step returns to the
   * list (the caller ends the live invite, so nothing is kept), and from the list it's null —
   * leave this screen. One screen per press, never the whole Calls screen.
   */
  fun back(): PortalPinFlowState? = when {
    removing != null -> cancelRemove()
    stage == PortalPinStage.LIST -> null
    else -> list()
  }
}

/** Words for the Portal pairing screens, in the approved mock's voice. Pure. */
internal object PortalPinCopy {
  const val TITLE = "Portals in this home"
  const val SUB = "Portals you paired in person share this home’s linked households"
  const val PAIR = "Pair this Portal with another Portal"
  const val PAIR_HOW = "Stand at both Portals. One shows a code, the other types it, then you check the same four pictures on both screens."
  const val SHOW = "Show a code"
  const val TYPE = "Type a code"
  const val PAIRED = "Paired Portals"
  const val NONE = "No Portals paired yet"
  const val REMOVE = "Remove"
  const val SHOW_TITLE = "Pair with another Portal"
  const val SHOW_H2 = "Type this code on the other Portal"
  const val SHOW_HOW = "On the other Portal: Calls › Link homes test › This home › Pair Portals › Type a code."
  const val WAITING = "Waiting for the other Portal…"
  const val ONCE = "Works once"
  const val SAME_ROOM = "Both Portals in one room"
  const val NOTE = "The code only introduces the two Portals. Next, both screens show four pictures: they must match."
  const val TYPE_TITLE = "Pair with another Portal"
  const val TYPE_H2 = "Type the code shown on the other Portal"
  const val TYPE_HOW = "Pairing codes start with P and are 10 letters and numbers."
  const val PAIR_BUTTON = "Pair"
  /** A home-link code typed on the pairing keypad. */
  const val HOME_CODE_HERE = "That’s a home-link code. Use Join with a code in Households instead."
  const val JOINING = "Finding that Portal…"
  const val NOT_FOUND = "No Portal in this home is showing that code. Check it, and that both are on the same Wi-Fi."
  const val NO_ANSWER = "That Portal didn’t take the code. Check it and try again, or make a new code."
  const val VERIFY_LEDE = "Both Portals show the same four pictures and numbers if they’re really talking to each other. Compare the two screens side by side."
  const val REPAIR_TITLE = "Pair this Portal again?"
  const val REPAIR_GO = "Pair again"
  const val CANCEL = "Cancel"
  const val CLOSE = "Close"
  const val DONE = "Done"
  const val OUT_OF_DATE = "a certificate is out of date"
  const val NOT_READY = "This Portal can’t pair yet: its device key or household id isn’t ready."

  fun repairBody(name: String): String =
      "$name is already paired, but with a different key. That happens after a reset. If you just reset it, pair again; otherwise cancel."

  fun doneTitle(name: String): String = "$name is paired"

  fun doneBody(name: String): String =
      "This Portal now shares linked households with $name. Pair it on the other Portal’s screen too if it didn’t finish there."

  fun removeBody(name: String): String =
      "This Portal stops sharing linked households with $name. To share again, pair them in person."

  fun failure(f: HomeLinkFailure): HomeLinkCopy.Failure = when (f) {
    HomeLinkFailure.EXPIRED -> HomeLinkCopy.Failure("This code ran out",
        "Pairing codes work for 5 minutes and only once. Nothing was shared. Make a new code.")
    else -> HomeLinkCopy.failure(f)
  }

  /** The line under a paired Portal: its household id and what its attestation showed. */
  fun pinDetail(pin: PortalPin): String =
      "${pin.deviceId} · ${attestation(pin.attestation)}" + if (pin.attestationOutOfDate) " · $OUT_OF_DATE" else ""

  fun attestation(level: PortalAttestationLevel): String = when (level) {
    PortalAttestationLevel.STRONGBOX -> "Key in StrongBox"
    PortalAttestationLevel.TEE -> "Key in secure hardware"
    PortalAttestationLevel.SOFTWARE -> "Key in software"
    PortalAttestationLevel.UNTRUSTED_ROOT -> "Key attested by its maker"
    PortalAttestationLevel.ABSENT -> "Key not attested"
    PortalAttestationLevel.INVALID -> "Attestation didn’t check out"
    PortalAttestationLevel.UNCHECKED -> "Attestation not checked"
  }
}
