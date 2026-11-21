/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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

/** What the screens need from this Portal, made off the main thread (Keystore and sealed file reads). */
private class PortalPinDeps(val ceremony: PortalPinCeremony, val store: PortalRosterStore, val selfName: String)

/**
 * "Pair this Portal with another Portal" (calling-phase-2.md S3b), measurement builds only like
 * the vault it unlocks ([HomeLinkGate.VAULT_SYNC]): the list of Portals paired in person, Show a
 * code / Type a code over the LAN mailbox, the four pictures + digits, then the pin. Protocol and
 * storage run on IO; state is [PortalPinFlowState]. Never logs codes.
 */
@Composable
internal fun PortalPinFlowScreen(feature: HomeLinkFeature, onClose: () -> Unit) {
  val context = LocalContext.current
  val p = LocalApprovedPalette.current
  val scope = rememberCoroutineScope()
  var deps by remember { mutableStateOf<PortalPinDeps?>(null) }
  var notReady by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    deps = withContext(Dispatchers.IO) { buildDeps(context, feature) }
    notReady = deps == null
  }
  val d = deps
  if (d == null) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(p.background)) {
      if (notReady) Text(PortalPinCopy.NOT_READY, color = p.error, fontSize = 28.sp, modifier = Modifier.align(Alignment.Center).padding(56.dp))
    }
    return
  }
  var state by remember { mutableStateOf(PortalPinFlowState()) }
  var roster by remember { mutableStateOf(d.store.roster) }
  var now by remember { mutableLongStateOf(feature.clock()) }
  DisposableEffect(Unit) { onDispose { d.ceremony.end() } }
  LaunchedEffect(Unit) { while (true) { now = feature.clock(); delay(TICK_MS) } }
  // The showing side learns of the other Portal through its own mailbox: poll its inviter.
  LaunchedEffect(state.stage, state.invite) {
    while (state.stage == PortalPinStage.SHOW) {
      withContext(Dispatchers.IO) { d.ceremony.inviterState() }?.let { state = state.inviter(it) }
      delay(POLL_MS)
    }
  }
  fun io(block: () -> Unit) { scope.launch(Dispatchers.IO) { block() } }
  fun show() = io { val invite = d.ceremony.show(); scope.launch { state = state.showCode(invite) } }
  fun back() { d.ceremony.end(); state = state.list(); roster = d.store.roster }
  fun confirm(repair: Boolean) {
    val side = state.side ?: return
    io {
      val outcome = d.ceremony.confirm(side, repair)
      scope.launch { state = state.pinned(outcome); roster = d.store.roster }
    }
  }
  // Back steps back one screen ([PortalPinFlowState.back]); from the list it leaves Pair Portals.
  BackHandler {
    val next = state.back()
    when {
      next == null -> onClose()
      next.stage == PortalPinStage.LIST && state.stage != PortalPinStage.LIST -> back()
      else -> state = next
    }
  }
  Box(Modifier.fillMaxSize().background(p.background)) {
    when (state.stage) {
      PortalPinStage.LIST -> PortalPinListScreen(d.selfName, roster.sorted, state.removing, PortalPinListActions(
          onShow = ::show, onType = { state = state.typeCode() }, onAskRemove = { state = state.askRemove(it) },
          onRemove = { kid -> io { d.store.unpin(kid); scope.launch { roster = d.store.roster; state = state.cancelRemove() } } },
          onCancelRemove = { state = state.cancelRemove() }, onBack = onClose))
      PortalPinStage.SHOW -> state.invite?.let { PortalPinShowScreen(it, now, onNewCode = ::show, onCancel = ::back) }
      PortalPinStage.TYPE, PortalPinStage.JOINING -> {
        HomeLinkJoinScreen(state.entry, onEntry = { state = state.typed(it) }, onJoin = { code ->
          state = state.joining()
          io { val r = d.ceremony.join(code); scope.launch { state = state.joined(r) } }
        }, onBack = ::back, words = HomeLinkJoinWords.PORTAL_PIN)
        val line = if (state.stage == PortalPinStage.JOINING) PortalPinCopy.JOINING else state.note
        line?.let { Text(it, color = if (state.note != null) p.error else p.secondary, fontSize = 24.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(40.dp)) }
      }
      PortalPinStage.VERIFY -> {
        val peer = state.peer
        val safety = state.safety
        if (peer == null || safety == null) LaunchedEffect(Unit) { back() }
        else HomeLinkVerifyScreen(peer.name, safety, onMatch = { confirm(repair = false) },
            onMismatch = { d.ceremony.end(); state = state.mismatch() }, lede = PortalPinCopy.VERIFY_LEDE)
      }
      PortalPinStage.REPAIR -> PortalPinRepairScreen(state.peer?.name.orEmpty(), onRepair = { confirm(repair = true) }, onCancel = ::back)
      PortalPinStage.DONE -> PortalPinDoneScreen(state.peer?.name.orEmpty(), onDone = ::back)
      PortalPinStage.FAILED -> HomeLinkFailureScreen(state.failure, onStartAgain = { back(); show() }, onClose = ::back,
          words = PortalPinCopy.failure(state.failure))
    }
  }
}

/** This Portal's pinning parts, or null when it has no device key or household id yet. */
private fun buildDeps(context: android.content.Context, feature: HomeLinkFeature): PortalPinDeps? {
  val device = PortalDeviceKey.get() ?: return null
  val id = PortalPinNet.selfId(context)?.takeIf(HomeLinkWire::validDevice) ?: return null
  val name = DinnerCall.selfName(context).take(HomeLinkWire.MAX_NAME)
  val store = PortalRosterStore.get(context)
  val ceremony = PortalPinCeremony(HomeLinkSelf(device, name, id), store, PortalPinNet.mailbox, PortalPinNet.transport(context),
      PortalPinNet::peers, device::attestationChain, feature.random, feature.clock, PortalDeviceKey.CHALLENGE)
  return PortalPinDeps(ceremony, store, name)
}

private const val TICK_MS = 1_000L
private const val POLL_MS = 500L
