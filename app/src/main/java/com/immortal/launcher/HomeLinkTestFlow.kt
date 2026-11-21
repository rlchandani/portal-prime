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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Where the "Link homes test" is (mock flow: hh → invite → join → verify → perms → invitedone → hh). */
internal enum class HomeLinkTestStage { HOUSEHOLDS, INVITE, JOIN, VERIFY, PERMS, ROOMS, CONNECTED, FAILED }

/**
 * Measurement builds only ([BuildConfig.PORTAL_CALL_LOOPBACK]): Households & devices with the real
 * sealed store ([HomeLinkRepository]) and sync, and "Add a household" running both homes on this
 * Portal through [HomeLinkLoopback]. This home links with its real identity; "Lakeview" is a
 * throwaway key, so Remove it when done. Store reads and writes run off the main thread.
 */
@Composable
internal fun HomeLinkLoopbackScreen(feature: HomeLinkFeature, onClose: () -> Unit) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val scope = rememberCoroutineScope()
  val random = feature.random
  var repo by remember { mutableStateOf<HomeLinkRepository?>(null) }
  LaunchedEffect(Unit) { repo = withContext(Dispatchers.IO) { HomeLinkRepository.get(context) } }
  val store = repo ?: return Box(Modifier.fillMaxSize().background(p.background))
  val vault by store.vault.collectAsState()
  val selfName = remember { DinnerCall.selfName(context) }
  val rooms = CallingStore.peers.collectAsState().value
  var households by remember { mutableStateOf(HomeLinkHouseholdsState(vault.book)) }
  LaunchedEffect(vault) { households = households.withBook(vault.book) }
  var stage by remember { mutableStateOf(HomeLinkTestStage.HOUSEHOLDS) }
  var invite by remember { mutableStateOf(HomeLinkInvite(HomeLinkCode.generate(random), feature.clock())) }
  var now by remember { mutableLongStateOf(feature.clock()) }
  var entry by remember { mutableStateOf(HomeLinkCodeEntry()) }
  var result by remember { mutableStateOf<HomeLinkLoopback.Result?>(null) }
  // The invite's one live inviter: wrong codes add up across joins until the third burns it.
  var inviter by remember { mutableStateOf<HomeLinkInviterSession?>(null) }
  var failure by remember { mutableStateOf(HomeLinkFailure.MISMATCH) }
  var typed by remember { mutableStateOf<HomeLinkCode?>(null) }
  var qr by remember { mutableStateOf<Bitmap?>(null) }
  var noAnswer by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf<HomeLink?>(null) }
  val roomNames = listOf(selfName) + rooms.map { it.name }
  fun write(block: (HomeLinkRepository) -> Unit) { scope.launch(Dispatchers.IO) { block(store) } }
  LaunchedEffect(invite) { qr = withContext(Dispatchers.Default) { lanSetupQr(HomeLinkCopy.qrText(invite.code), QR_PX) } }
  LaunchedEffect(Unit) { while (true) { now = feature.clock(); delay(TICK_MS) } }
  LaunchedEffect(now, stage) {
    if (stage == HomeLinkTestStage.INVITE && invite.isExpired(now)) { failure = HomeLinkFailure.EXPIRED; stage = HomeLinkTestStage.FAILED }
  }
  LaunchedEffect(typed) {
    val code = typed ?: return@LaunchedEffect
    val live = inviter
    val (session, r) = withContext(Dispatchers.IO) {
      val me = store.identity() ?: return@withContext live to null
      val session = live ?: HomeLinkInviterSession.start(invite, HomeLinkSelf(me, selfName), random)
      session to HomeLinkLoopback.exchange(session, code, HomeLinkSelf(HomeLinkIdentity.generate(random), TEST_PEER), random,
          feature.clock())
    }
    inviter = session
    typed = null
    result = r
    when (val i = r?.inviter) {
      is HomeLinkInviterState.Comparing -> stage = HomeLinkTestStage.VERIFY
      is HomeLinkInviterState.Failed -> { failure = i.reason; stage = HomeLinkTestStage.FAILED }
      else -> noAnswer = true
    }
  }
  fun newInvite() {
    invite = HomeLinkInvite(HomeLinkCode.generate(random), feature.clock())
    entry = HomeLinkCodeEntry(); result = null; inviter = null; noAnswer = false; stage = HomeLinkTestStage.INVITE
  }
  Box(Modifier.fillMaxSize().background(p.background)) {
    when (stage) {
      HomeLinkTestStage.HOUSEHOLDS -> HomeLinkHouseholdsScreen(households, selfName,
          HomeLinkDevices.ofThisHome(selfName, rooms, now), ZoneId.systemDefault(), HomeLinkHouseholdsActions(
          onState = { households = it }, onEdit = { l -> write { it.edit(l) } }, onRemove = { id -> write { it.remove(id) } },
          onInvite = ::newInvite, onJoin = ::newInvite, onRooms = { editing = it; stage = HomeLinkTestStage.ROOMS },
          onCheckAgain = { newInvite() }, onBack = onClose))
      HomeLinkTestStage.INVITE -> {
        HomeLinkInviteScreen(invite, now, qr, onNewCode = ::newInvite, onCancel = { stage = HomeLinkTestStage.HOUSEHOLDS })
        CallPill("Type it as the other home", { stage = HomeLinkTestStage.JOIN }, icon = "house", style = CallButtonStyle.PRIMARY,
            surface = callsSurface(), modifier = Modifier.align(Alignment.BottomEnd).padding(40.dp))
      }
      HomeLinkTestStage.JOIN -> {
        HomeLinkJoinScreen(entry, onEntry = { entry = it; noAnswer = false }, onJoin = { typed = it },
            onBack = { stage = HomeLinkTestStage.INVITE })
        if (noAnswer) Text(HomeLinkCopy.NO_ANSWER, color = p.error, fontSize = 24.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(40.dp))
      }
      HomeLinkTestStage.VERIFY -> {
        val comparing = result?.inviter as? HomeLinkInviterState.Comparing
        if (comparing == null) LaunchedEffect(Unit) { stage = HomeLinkTestStage.HOUSEHOLDS }
        else HomeLinkVerifyScreen(comparing.peer.name, comparing.safety, onMatch = {
          val peer = comparing.peer
          write { r -> r.link(peer, HomeLinkPermissions.defaults(roomNames, DEFAULT_QUIET))?.let { editing = it } }
          stage = HomeLinkTestStage.PERMS
        }, onMismatch = { failure = HomeLinkFailure.MISMATCH; stage = HomeLinkTestStage.FAILED })
      }
      HomeLinkTestStage.PERMS, HomeLinkTestStage.ROOMS -> {
        val link = editing?.let { e -> vault.book.links.firstOrNull { it.id == e.id } }
        if (link == null) Box(Modifier.fillMaxSize())
        else HomeLinkPermissionsScreen(link, selfName, roomNames, onEdit = { l -> write { it.edit(l) } },
            onDone = { stage = if (stage == HomeLinkTestStage.PERMS) HomeLinkTestStage.CONNECTED else HomeLinkTestStage.HOUSEHOLDS },
            onBack = if (stage == HomeLinkTestStage.ROOMS) ({ stage = HomeLinkTestStage.HOUSEHOLDS }) else null)
      }
      HomeLinkTestStage.CONNECTED -> HomeLinkConnectedScreen(editing?.name ?: TEST_PEER, onDone = {
        households = households.withBook(vault.book).select(editing?.id); stage = HomeLinkTestStage.HOUSEHOLDS
      })
      HomeLinkTestStage.FAILED -> HomeLinkFailureScreen(failure, onStartAgain = ::newInvite, onClose = { stage = HomeLinkTestStage.HOUSEHOLDS })
    }
  }
}

private const val TEST_PEER = "Lakeview"
/** Mock `permsScr`: Kids' room and Office are off by default (owner decision D5 pending). */
private val DEFAULT_QUIET = setOf("Kids’ room", "Office")
private const val QR_PX = 412
private const val TICK_MS = 1_000L
