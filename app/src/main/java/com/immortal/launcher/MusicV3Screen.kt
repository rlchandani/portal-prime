/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.youtube.YtAndroid
import com.immortal.launcher.youtube.YtSignInState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Music v3 (official YouTube route): one full-screen page. Shows NotConfigured, the QR sign-in or
 * "No one is signed in" under a simple header, or the full-screen player stage ([MusicV3PlayerLayout]).
 * [resumeOnOpen] = opened from Home's Now playing chip, which resumes the paused song.
 */
@Composable
fun MusicV3Screen(roomName: String, resumeOnOpen: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val library = remember(context) { YtAndroid.library(context) }
  val accounts by library.accounts.collectAsState()
  val active by library.activeAccount.collectAsState()
  val signIn by library.signInState.collectAsState()
  var adding by remember { mutableStateOf(false) }
  var signedOutLast by remember { mutableStateOf(false) }
  val hasQueue by remember { derivedQueuePresence() }.collectAsState(initial = !YouTubeMusicEngine.state.value.queue.isEmpty)
  val gate = MusicV3Rules.gate(library.isConfigured, accounts, active, adding, signedOutLast,
      showingSuccess = signIn is YtSignInState.SignedIn, hasQueue = hasQueue)
  val keyboard = rememberMusicV3KeyboardPolicy()
  BackHandler(enabled = gate == MusicV3Gate.ADD_ACCOUNT) { adding = false }
  val focus = LocalFocusManager.current
  // Drawn over the launcher: swallow stray taps (they'd reach Home below) and drop the keyboard.
  val root = modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }
  if (gate == MusicV3Gate.PLAYER || gate == MusicV3Gate.GUEST) {
    // The full-screen stage draws its own top bar (back, wordmark, clock, account) and backdrop.
    Box(root) {
      androidx.compose.runtime.CompositionLocalProvider(LocalMusicKeyboardPolicy provides keyboard) {
        MusicV3PlayerLayout(MusicV3StageHost(
            library = library, accounts = accounts, active = active.takeIf { gate == MusicV3Gate.PLAYER },
            room = roomName, resumeOnOpen = resumeOnOpen, onBack = onClose,
            onAddAccount = { adding = true }, onLastSignedOut = { signedOutLast = true }))
      }
    }
    return
  }
  Column(root.background(ApprovedHomePalette.Background)) {
    MusicV3Header(
        onBack = onClose,
        onCancel = if (gate == MusicV3Gate.ADD_ACCOUNT && MusicV3Rules.canCancelSignIn(accounts.size, hasQueue)) {
          { adding = false }
        } else null)
    Box(Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
      when (gate) {
        MusicV3Gate.NOT_CONFIGURED -> MusicV3NotConfigured()
        MusicV3Gate.SIGN_IN, MusicV3Gate.ADD_ACCOUNT ->
          MusicV3SignIn(library, adding = gate == MusicV3Gate.ADD_ACCOUNT && accounts.isNotEmpty()) {
            adding = false
            signedOutLast = false
          }
        MusicV3Gate.NO_ONE -> MusicV3NoOne { signedOutLast = false }
        MusicV3Gate.PLAYER, MusicV3Gate.GUEST -> Unit
      }
    }
  }
}

@Composable
private fun MusicV3Header(onBack: () -> Unit, onCancel: (() -> Unit)?) {
  // Back control sits on its own row above the title so the "Music" title's left edge lines up with
  // the content column below (both inset 24.dp), rather than being pushed right by the back button.
  Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      MusicV3RoundButton("Back to Home", Modifier.clip(CircleShape).approvedOutline(CircleShape), size = 68.dp,
          fill = ApprovedHomePalette.Card, onClick = onBack) {
        ApprovedIcon("back", Modifier.size(30.dp), ApprovedHomePalette.Text)
      }
      Spacer(Modifier.weight(1f))
      onCancel?.let { MusicV3Button("Cancel", onClick = it) }
    }
    Text("Music", color = ApprovedHomePalette.Text, fontSize = 32.sp, fontWeight = FontWeight.Medium)
  }
}

/** Whether the engine holds a queue; only changes when it empties or fills, not on every tick. */
private fun derivedQueuePresence() = YouTubeMusicEngine.state.map { !it.queue.isEmpty }.distinctUntilChanged()
