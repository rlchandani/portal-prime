/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.youtube.YtAccount
import com.immortal.launcher.youtube.YtAndroid
import com.immortal.launcher.youtube.YtSignInState

/**
 * Music v3 (official YouTube route): one full-screen page with its own header. Shows NotConfigured,
 * the QR sign-in, "No one is signed in", or the player (Now Playing + Queue / Library / Search).
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
  var accountsOpen by remember { mutableStateOf(false) }
  val gate = MusicV3Rules.gate(library.isConfigured, accounts, active, adding, signedOutLast,
      showingSuccess = signIn is YtSignInState.SignedIn)
  rememberMusicV3KeyboardPolicy()
  BackHandler(enabled = gate == MusicV3Gate.ADD_ACCOUNT) { adding = false }
  val focus = LocalFocusManager.current
  // Drawn over the launcher: swallow stray taps (they'd reach Home below) and drop the keyboard.
  Column(modifier.fillMaxSize().background(ApprovedHomePalette.Background)
      .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }) {
    MusicV3Header(
        onBack = onClose,
        account = active.takeIf { gate == MusicV3Gate.PLAYER },
        onAccounts = { accountsOpen = !accountsOpen },
        onCancel = if (gate == MusicV3Gate.ADD_ACCOUNT && accounts.isNotEmpty()) ({ adding = false }) else null)
    Box(Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
      when (gate) {
        MusicV3Gate.NOT_CONFIGURED -> MusicV3NotConfigured()
        MusicV3Gate.SIGN_IN, MusicV3Gate.ADD_ACCOUNT ->
          MusicV3SignIn(library, adding = gate == MusicV3Gate.ADD_ACCOUNT && accounts.isNotEmpty()) {
            adding = false
            signedOutLast = false
          }
        MusicV3Gate.NO_ONE -> MusicV3NoOne { signedOutLast = false }
        MusicV3Gate.PLAYER -> MusicV3PlayerLayout(
            library = library, accounts = accounts, active = active, room = roomName, resumeOnOpen = resumeOnOpen,
            accountsOpen = accountsOpen, onAccountsOpen = { accountsOpen = it },
            onAddAccount = { accountsOpen = false; adding = true },
            onLastSignedOut = { signedOutLast = true })
      }
    }
  }
}

@Composable
private fun MusicV3Header(onBack: () -> Unit, account: YtAccount?, onAccounts: () -> Unit, onCancel: (() -> Unit)?) {
  Row(Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    MusicV3RoundButton("Back to Home", Modifier.clip(CircleShape).approvedOutline(CircleShape), size = 68.dp,
        fill = ApprovedHomePalette.Card, onClick = onBack) {
      ApprovedIcon("back", Modifier.size(30.dp), ApprovedHomePalette.Text)
    }
    Text("Music", color = ApprovedHomePalette.Text, fontSize = 32.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.weight(1f))
    when {
      onCancel != null -> MusicV3Button("Cancel", onClick = onCancel)
      account != null -> AccountChip(account, onAccounts)
    }
  }
}

@Composable
private fun AccountChip(account: YtAccount, onClick: () -> Unit) {
  val shape = RoundedCornerShape(34.dp)
  Row(Modifier.height(68.dp).clip(shape).background(ApprovedHomePalette.Card).approvedOutline(shape)
      .tvFocusable(shape, ringColor = ApprovedHomePalette.Accent, onClick = onClick)
      .semantics { contentDescription = "Accounts, using ${account.title}" }.padding(start = 9.dp, end = 22.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    MusicV3Avatar(account.title, account.thumbnailUrl, 48.dp)
    Text(account.title, color = ApprovedHomePalette.Text, fontSize = 23.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    MusicV3GlyphIcon(MusicV3Glyph.DOWN, ApprovedHomePalette.Secondary, Modifier.size(24.dp))
  }
}

/**
 * While music is on screen the soft keyboard overlays it (ADJUST_NOTHING) instead of panning the
 * launcher, so it rises from the bottom and stops below the player; restored on close.
 */
@Composable
private fun rememberMusicV3KeyboardPolicy() {
  val view = LocalView.current
  DisposableEffect(view) {
    val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()?.window
    val previous = window?.attributes?.softInputMode
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    onDispose { if (window != null && previous != null) window.setSoftInputMode(previous) }
  }
}
