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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.youtube.YouTubeMusicLibrary
import com.immortal.launcher.youtube.YtAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TOAST_MS = 5_000L
private val LeftCardWidth = 800.dp

/** Signed in: Now Playing on the left (never covered), Queue / Library / Search on the right. */
@Composable
internal fun MusicV3PlayerLayout(
    library: YouTubeMusicLibrary,
    accounts: List<YtAccount>,
    active: YtAccount?,
    room: String,
    resumeOnOpen: Boolean,
    accountsOpen: Boolean,
    onAccountsOpen: (Boolean) -> Unit,
    onAddAccount: () -> Unit,
    onLastSignedOut: () -> Unit,
) {
  val engine by YouTubeMusicEngine.state.collectAsState()
  var toast by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(toast) { if (toast != null) { delay(TOAST_MS); toast = null } }
  VideoScreenGuards(engine, resumeOnOpen)
  SkipToasts(engine.lastSkip) { toast = it }
  Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(MusicV3Dims.Gap)) {
    MusicV3NowPlayingCard(engine, room, Modifier.width(LeftCardWidth).fillMaxHeight())
    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(MusicV3Dims.PaneRadius))
        .background(ApprovedHomePalette.Card)) {
      RightPane(library, active, engine.queue, onToast = { toast = it }, onSignInAgain = onAddAccount)
      toast?.let { MusicV3Toast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp, start = 24.dp, end = 24.dp)) }
      if (accountsOpen) {
        AccountsOverlay(library, accounts, active, engine.queue, onClose = { onAccountsOpen(false) },
            onAdd = onAddAccount, onLastSignedOut = onLastSignedOut, onToast = { toast = it })
      }
    }
  }
}

/**
 * The screensaver must not start over a playing video (keep the screen awake and tell
 * [DreamPolicy]); leaving while playing pauses (the surface does that) and Home explains why once.
 */
@Composable
private fun VideoScreenGuards(engine: YtEngineState, resumeOnOpen: Boolean) {
  val holds = YtVideoScreenState.holds(engine)
  if (holds) KeepInteractiveScreenAwake()
  DisposableEffect(holds) {
    YtVideoScreenState.playingOnScreen = holds
    onDispose { YtVideoScreenState.playingOnScreen = false }
  }
  val wasPlaying by rememberUpdatedState(engine.isPlaying || engine.wantsPlay)
  DisposableEffect(Unit) { onDispose { if (wasPlaying) MusicV3Session.leftWhilePlaying() } }
  LaunchedEffect(Unit) {
    if (!resumeOnOpen) return@LaunchedEffect
    YouTubeMusicEngine.state.first { it.foreground }
    YouTubeMusicEngine.play()
  }
}

/** One toast per auto-skip that happens while this screen is open. */
@Composable
private fun SkipToasts(skip: YtSkipNotice?, show: (String) -> Unit) {
  val seenAtOpen = remember { skip?.seq ?: 0 }
  LaunchedEffect(skip?.seq) {
    if (skip != null && skip.seq > seenAtOpen) show(MusicV3Rules.skipToast(skip))
  }
}

@Composable
private fun RightPane(
    library: YouTubeMusicLibrary,
    active: YtAccount?,
    queue: YtQueue,
    onToast: (String) -> Unit,
    onSignInAgain: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val actions = remember(library, scope) { MusicV3Actions(library, scope) { onToast(it) } }
  var pane by rememberSaveable { mutableStateOf(if (queue.isEmpty) MusicV3Pane.LIBRARY else MusicV3Pane.QUEUE) }
  var nav by remember(active?.id) { mutableStateOf(MusicV3LibraryNav()) }
  // Hoisted so switching tabs keeps the shown results (a new search would cost budget).
  val search = remember { mutableStateOf(MusicV3SearchState(recent = MusicV3Session.recentSearches)) }
  BackHandler(enabled = pane == MusicV3Pane.LIBRARY && nav.inDetail) { nav = nav.back() }
  Column(Modifier.fillMaxSize()) {
    Segmented(pane) { pane = it }
    Box(Modifier.weight(1f).padding(start = 32.dp, end = 32.dp, top = 24.dp)) {
      when (pane) {
        MusicV3Pane.QUEUE -> MusicV3QueuePane(queue) { pane = MusicV3Pane.LIBRARY }
        MusicV3Pane.LIBRARY -> if (active != null) {
          MusicV3LibraryPane(library, active.id, nav, { nav = it }, actions, onSignInAgain)
        }
        MusicV3Pane.SEARCH -> SearchTab(library, actions, search)
      }
    }
  }
}

@Composable
private fun SearchTab(
    library: YouTubeMusicLibrary,
    actions: MusicV3Actions,
    holder: androidx.compose.runtime.MutableState<MusicV3SearchState>,
) {
  val scope = rememberCoroutineScope()
  val keyboard = LocalSoftwareKeyboardController.current
  var search by holder
  LaunchedEffect(Unit) {
    val left = withContext(Dispatchers.IO) { library.searchesRemainingToday() }
    search = search.copy(remaining = left)
  }
  MusicV3SearchPane(search, onQuery = { search = search.copy(query = it) }, actions = actions, onSubmit = { q ->
    keyboard?.hide()
    search = search.searching(q)
    scope.launch {
      val result = library.search(q)
      val left = withContext(Dispatchers.IO) { library.searchesRemainingToday() }
      search = search.answered(q, result, left)
      MusicV3Session.recentSearches = search.recent
    }
  })
}

@Composable
private fun Segmented(selected: MusicV3Pane, onSelect: (MusicV3Pane) -> Unit) {
  Row(Modifier.padding(start = 32.dp, top = 28.dp).clip(RoundedCornerShape(42.dp)).background(ApprovedHomePalette.Chrome)
      .padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    MusicV3Pane.entries.forEach { pane ->
      val on = pane == selected
      val shape = RoundedCornerShape(36.dp)
      Box(Modifier.height(72.dp).widthIn(min = 200.dp).clip(shape)
          .background(if (on) ApprovedHomePalette.Card else Color.Transparent)
          .then(if (on) Modifier.approvedOutline(shape) else Modifier)
          .tvFocusable(shape, ringColor = ApprovedHomePalette.Accent) { onSelect(pane) }
          .semantics { role = Role.Tab }.padding(horizontal = 30.dp), contentAlignment = Alignment.Center) {
        Text(pane.label, color = if (on) ApprovedHomePalette.Text else ApprovedHomePalette.Secondary, fontSize = 25.sp,
            fontWeight = FontWeight.Medium)
      }
    }
  }
}

/** Accounts panel wiring: switch keeps the music; signing out the account in use stops it. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.AccountsOverlay(
    library: YouTubeMusicLibrary,
    accounts: List<YtAccount>,
    active: YtAccount?,
    queue: YtQueue,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    onLastSignedOut: () -> Unit,
    onToast: (String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  var confirming by remember { mutableStateOf<YtAccount?>(null) }
  BackHandler { if (confirming != null) confirming = null else onClose() }
  val actions = MusicV3AccountActions(
      switchTo = { a ->
        onClose()
        scope.launch { if (library.switchAccount(a.id)) onToast("Now using ${a.title}'s library") }
      },
      askSignOut = { confirming = it },
      confirmSignOut = { a ->
        confirming = null
        if (a.id == active?.id) YouTubeMusicEngine.clear()
        if (accounts.size <= 1) onLastSignedOut()
        scope.launch {
          library.signOut(a.id)
          onToast("${a.title} is signed out")
        }
      },
      cancelSignOut = { confirming = null },
      add = onAdd,
      close = onClose,
  )
  MusicV3AccountsPanel(accounts, active, confirming, !queue.isEmpty, actions)
}
