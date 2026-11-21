/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Measurement builds only ([BuildConfig.PORTAL_CALL_LOOPBACK]): both homes on this one Portal, so
 * the linking screens and protocol can be tried before there is a relay. Every message goes
 * through [HomeLinkWire] as it would over a relay. Ephemeral keys; nothing is stored.
 */
internal object HomeLinkLoopback {
  data class Result(val inviter: HomeLinkInviterState, val joiner: HomeLinkJoinerState)

  fun exchange(invite: HomeLinkInvite, typed: HomeLinkCode, inviter: HomeLinkSelf, joiner: HomeLinkSelf, random: SecureRandom,
               now: Long): Result {
    var i = HomeLinkInviter.start(invite)
    val j1 = HomeLinkJoiner.start(typed, joiner, random)
    var j = j1.state
    var toInviter: HomeLinkMessage? = j1.outgoing
    while (toInviter != null) {
      val iStep = HomeLinkInviter.receive(i, inviter, relay(toInviter) ?: break, now)
      i = iStep.state
      val toJoiner = iStep.outgoing?.let(::relay) ?: break
      val jStep = HomeLinkJoiner.receive(j, joiner, toJoiner)
      j = jStep.state
      toInviter = jStep.outgoing
    }
    return Result(i, j)
  }

  private fun relay(m: HomeLinkMessage): HomeLinkMessage? = HomeLinkWire.parse(HomeLinkWire.encode(m))
}

private enum class LinkTestStage { INVITE, JOIN, VERIFY, CONNECTED, FAILED }

/** The "Link homes test" overlay: Invite → Join (as the other home) → Verify → Connected or stopped. */
@Composable
internal fun HomeLinkLoopbackScreen(onClose: () -> Unit) {
  val p = LocalApprovedPalette.current
  val random = remember { SecureRandom() }
  var invite by remember { mutableStateOf(HomeLinkInvite(HomeLinkCode.generate(random), System.currentTimeMillis())) }
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var stage by remember { mutableStateOf(LinkTestStage.INVITE) }
  var entry by remember { mutableStateOf(HomeLinkCodeEntry()) }
  var result by remember { mutableStateOf<HomeLinkLoopback.Result?>(null) }
  var failure by remember { mutableStateOf(HomeLinkFailure.MISMATCH) }
  var typed by remember { mutableStateOf<HomeLinkCode?>(null) }
  var qr by remember { mutableStateOf<Bitmap?>(null) }
  var noAnswer by remember { mutableStateOf(false) }
  LaunchedEffect(invite) { qr = withContext(Dispatchers.Default) { lanSetupQr(HomeLinkCopy.qrText(invite.code), QR_PX) } }
  LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(TICK_MS) } }
  LaunchedEffect(now, stage) {
    if (stage == LinkTestStage.INVITE && invite.isExpired(now)) { failure = HomeLinkFailure.EXPIRED; stage = LinkTestStage.FAILED }
  }
  LaunchedEffect(typed) {
    val code = typed ?: return@LaunchedEffect
    val r = withContext(Dispatchers.Default) {
      HomeLinkLoopback.exchange(invite, code, HomeLinkSelf(HomeLinkIdentity.generate(random), "Maple House"),
          HomeLinkSelf(HomeLinkIdentity.generate(random), "Lakeview"), random, System.currentTimeMillis())
    }
    typed = null
    result = r
    val i = r.inviter
    when {
      i is HomeLinkInviterState.Comparing -> stage = LinkTestStage.VERIFY
      i is HomeLinkInviterState.Failed -> { failure = i.reason; stage = LinkTestStage.FAILED }
      else -> noAnswer = true
    }
  }
  fun restart() {
    invite = HomeLinkInvite(HomeLinkCode.generate(random), System.currentTimeMillis())
    entry = HomeLinkCodeEntry(); result = null; noAnswer = false; stage = LinkTestStage.INVITE
  }
  Box(Modifier.fillMaxSize().background(p.background)) {
    when (stage) {
      LinkTestStage.INVITE -> {
        HomeLinkInviteScreen(invite, now, qr, onNewCode = ::restart, onCancel = onClose)
        CallPill("Type it as the other home", { stage = LinkTestStage.JOIN }, icon = "house", style = CallButtonStyle.PRIMARY,
            surface = callsSurface(), modifier = Modifier.align(Alignment.BottomEnd).padding(40.dp))
      }
      LinkTestStage.JOIN -> {
        HomeLinkJoinScreen(entry, onEntry = { entry = it; noAnswer = false }, onJoin = { typed = it }, onBack = { stage = LinkTestStage.INVITE })
        if (noAnswer) Text(HomeLinkCopy.NO_ANSWER, color = p.error, fontSize = 24.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(40.dp))
      }
      LinkTestStage.VERIFY -> {
        val r = result
        val comparing = r?.inviter as? HomeLinkInviterState.Comparing
        if (r == null || comparing == null) LaunchedEffect(Unit) { restart() }
        else HomeLinkVerifyScreen(comparing.peer.name, comparing.safety, onMatch = {
          result = HomeLinkLoopback.Result(HomeLinkInviter.confirm(r.inviter, true), HomeLinkJoiner.confirm(r.joiner, true))
          stage = LinkTestStage.CONNECTED
        }, onMismatch = { failure = HomeLinkFailure.MISMATCH; stage = LinkTestStage.FAILED })
      }
      LinkTestStage.CONNECTED -> HomeLinkConnectedScreen(
          (result?.inviter as? HomeLinkInviterState.Linked)?.peer?.name ?: "", onDone = onClose)
      LinkTestStage.FAILED -> HomeLinkFailureScreen(failure, onStartAgain = ::restart, onClose = onClose)
    }
  }
}

private const val QR_PX = 412
private const val TICK_MS = 1_000L
