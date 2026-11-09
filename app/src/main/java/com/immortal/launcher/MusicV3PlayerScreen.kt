/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import com.immortal.launcher.youtube.YouTubeMusicLibrary
import com.immortal.launcher.youtube.YtAccount
import kotlinx.coroutines.flow.first

/** Accounts and navigation callbacks from [MusicV3Screen]. */
internal data class MusicV3StageHost(
    val library: YouTubeMusicLibrary,
    val accounts: List<YtAccount>,
    /** Null in the guest gate (a queue moved here plays without an account). */
    val active: YtAccount?,
    val room: String,
    val resumeOnOpen: Boolean,
    val onBack: () -> Unit,
    val onAddAccount: () -> Unit,
    val onLastSignedOut: () -> Unit,
)

/**
 * Signed in: the approved "Full-screen + pull-up" Music stage. Now Playing owns the screen; one
 * sheet pulled up from the bottom holds Up next · Library · Recently played · Search. One spring
 * moves the sheet edge and the ONE YouTube player together; the geometry keeps everything else off
 * the player's rectangle in every state ([MusicStageGeometry]).
 */
@Composable
internal fun MusicV3PlayerLayout(host: MusicV3StageHost) {
  val nowState = rememberNowSlice(host.room)
  val now by nowState
  val move by MusicHandoff.move.collectAsState()
  val follow by MusicV3FollowMeInScreen.ask.collectAsState()
  val context = LocalContext.current
  val toasts = remember { MusicStageToasts() }
  toasts.Effects()
  VideoScreenGuards(now, host.resumeOnOpen)
  SkipToasts(now.lastSkip) { toasts.show(it) }
  MoveNotices { toasts.show(it) }
  FollowNotices(toasts)
  var sheet by remember { mutableStateOf(MusicSheetMachine.initial(now.idle)) }
  LaunchedEffect(now.idle) { sheet = MusicSheetMachine.onLoadedChanged(sheet, now.idle) }
  val canMove = remember(context) { MusicHandoff.available(context) }
  val onMove = if (canMove && now.track != null && move == MusicMoveState.Idle) {
    { sheet = MusicSheetMachine.toggle(MusicSheetMachine.close(sheet), MusicPopover.MOVE) }
  } else null
  val moveControls = MusicV3MoveControls(move, onMove, onBringBack = { MusicHandoff.bringBack(context) })
  val guests = rememberGuestHides(GuestContent.MUSIC_LIBRARY)
  val scope = rememberCoroutineScope()
  val actions = remember(host.library, scope) {
    MusicV3Actions(host.library, scope, toast = { toasts.show(it) }, confirm = { toasts.show(it, long = false) })
  }
  var nav by remember(host.active?.id) { mutableStateOf(MusicV3LibraryNav()) }
  val search = remember { mutableStateOf(MusicV3SearchState()) }
  val reduced = rememberReducedMotion()
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  LaunchedEffect(sheet.open) { if (!sheet.open) { focusManager.clearFocus(); keyboard?.hide() } }
  BackHandler(enabled = sheet.popover != MusicPopover.NONE || sheet.open) { MusicSheetMachine.back(sheet)?.let { sheet = it } }
  BackHandler(enabled = sheet.open && sheet.tab == MusicSheetTab.LIBRARY && nav.inDetail) { nav = nav.back() }
  val minute = rememberMinuteClock()
  val quiet = rememberQuietNote(minute)
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val layout = remember(maxWidth, maxHeight) { MusicStageGeometry.layout(maxWidth.value, maxHeight.value) }
    val palette = rememberMusicStagePalette(now.track, now.model.isAd)
    val dark = LocalApprovedPalette.current.dark
    val ink = remember(palette, dark) { MusicStageColors.of(palette, dark) }
    val motion = rememberMusicStageMotion(reduced, sheet.open)
    LaunchedEffect(sheet.openTarget) { motion.follow(sheet.openTarget) }
    LaunchedEffect(sheet.focusTarget) { motion.focusTo(sheet.focusTarget) }
    val closedActive by remember(motion) { derivedStateOf { motion.progress.value < MusicStageGeometry.INTERACTIVE_SPLIT } }
    val peekActive by remember(motion) { derivedStateOf { motion.progress.value < PEEK_UNTIL } }
    val tabsActive by remember(motion) { derivedStateOf { motion.progress.value >= TABS_FROM } }
    var stage by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var util by remember { mutableStateOf<Rect?>(null) }
    val settle = { open: Boolean, velocity: Float ->
      sheet = if (open) MusicSheetMachine.open(sheet) else MusicSheetMachine.close(sheet)
      motion.settle(open, velocity)
    }
    val handed = move is MusicMoveState.Handed
    MusicStageTheme(ink, MusicStageDims.of(layout.spec)) {
      Box(Modifier.fillMaxSize().onGloballyPositioned { stage = it }) {
        MusicStageBackdrop(ink, reduced)
        ClosedLayer(layout, motion, closedActive) {
          val account = host.active.takeIf { !guests }
          MusicV3TopBar(layout.spec, minute, account, sheet.popover == MusicPopover.ACCOUNTS, host.onBack,
              onAccounts = { sheet = MusicSheetMachine.toggle(sheet, MusicPopover.ACCOUNTS) })
          if (now.idle) {
            val idle = MusicV3IdleInputs(host.library.takeIf { !guests }, host.active?.id?.takeIf { !guests }, actions,
                follow, quiet, minute, onOpenPlaylist = { pl ->
                  nav = MusicV3LibraryNav(playlist = pl)
                  sheet = MusicSheetMachine.open(sheet, MusicSheetTab.LIBRARY)
                }, onOpenLibrary = { sheet = MusicSheetMachine.open(sheet, MusicSheetTab.LIBRARY) })
            MusicV3IdleResumeCard(layout, idle, Modifier.rect(layout.resumeCard))
            MusicV3IdleColumn(idle, Modifier.rect(layout.column))
          } else {
            NowPlaying(layout, now, host.room, moveControls, quiet, reduced, handed,
                onSleep = { sheet = MusicSheetMachine.toggle(sheet, MusicPopover.SLEEP) },
                onToast = { toasts.show(it, long = false) },
                onUtilPlaced = { c -> stage?.let { s -> util = s.localBoundingBoxOf(c) } })
          }
        }
        StripLayer(layout, motion, closedActive, settle, onClose = { sheet = MusicSheetMachine.close(sheet) }) {
          MusicV3Strip(now, MusicV3StripInputs(moveControls, follow, sheet.searchFocused, onResume = latestResume(actions)))
        }
        MusicV3Sheet(layout, motion, sheet, MusicV3SheetInputs(now.idle, now.next, peekActive, tabsActive,
            onOpen = { sheet = MusicSheetMachine.open(sheet) }, onClose = { sheet = MusicSheetMachine.close(sheet) },
            onTab = { sheet = MusicSheetMachine.selectTab(sheet, it) }, onDragStart = {}, onSettled = settle)) {
          MusicV3SheetBody(sheet.tab, MusicV3BodyInputs(host.library, host.active, guests, now.queue, actions, nav,
              onNav = { nav = it }, search = search, onSignInAgain = host.onAddAccount, onMove = onMove,
              onOpenLibrary = { sheet = MusicSheetMachine.selectTab(sheet, MusicSheetTab.LIBRARY) },
              onSearchFocus = { sheet = MusicSheetMachine.focusSearch(sheet, it) },
              searchAutoFocus = sheet.open && sheet.tab == MusicSheetTab.SEARCH))
        }
        if (!sheet.open) {
          MusicV3StagePopover(layout, sheet.popover, MusicV3PopoverInputs(host.library, host.accounts, host.active,
              !now.queue.isEmpty, now.sleep, util, onDismiss = { sheet = MusicSheetMachine.dismissPopover(sheet) },
              onToast = { toasts.show(it) },
              onAddAccount = { sheet = MusicSheetMachine.dismissPopover(sheet); host.onAddAccount() },
              onLastSignedOut = host.onLastSignedOut,
              onMoveTo = { room -> sheet = MusicSheetMachine.dismissPopover(sheet); MusicHandoff.moveTo(context, room) }))
          follow?.takeIf { !now.idle && sheet.popover == MusicPopover.NONE }?.let { MusicV3FollowCard(layout, it) }
        }
        val shown = !now.idle && !(handed && !now.model.playing)
        val placeholder = when {
          handed -> MusicV3SlotPlaceholder.Moved((move as MusicMoveState.Handed).targetName, moveControls.onBringBack)
          now.idle && sheet.open -> MusicV3SlotPlaceholder.Empty
          else -> null
        }
        MusicV3PlayerSlot(layout, motion, shown, placeholder)
        toasts.Host(layout.toast(sheet.open, sheet.searchFocused), bottom = sheet.open && !sheet.searchFocused,
            end = sheet.open && sheet.searchFocused)
      }
    }
  }
}

/** Header-strip handover points of the spring (the mock's peek and tabs fades). */
private const val PEEK_UNTIL = 0.34f
private const val TABS_FROM = 0.3f

/** Places a composable in a geometry rect (dp). */
internal fun Modifier.rect(r: MusicRect): Modifier = stageOffset { r.left to r.top }.size(r.width.dp, r.height.dp)

@Composable
private fun NowPlaying(
    layout: MusicStageLayout,
    now: MusicNowSlice,
    room: String,
    move: MusicV3MoveControls,
    quiet: String?,
    reduced: Boolean,
    handed: Boolean,
    onSleep: () -> Unit,
    onToast: (String) -> Unit,
    onUtilPlaced: (LayoutCoordinates) -> Unit,
) {
  val live = now.model.playing && !handed && !now.model.isAd
  MusicV3HeroArt(now.track, now.model.isAd, live, handed, layout.art.dp, layout.spec.artRadius.dp, reduced,
      Modifier.stageOffset { layout.artRect.left to layout.artRect.top })
  layout.playerCaption?.takeIf { !handed }?.let { MusicV3PlayerCaption(Modifier.rect(it)) }
  MusicV3NowColumn(now, MusicV3NowInputs(room, move, quiet, onSleep, onToast, onUtilPlaced), Modifier.rect(layout.column))
}

/** Most recent household play, as a Resume action for the strip (null when there is none). */
@Composable
private fun latestResume(actions: MusicV3Actions): (() -> Unit)? {
  val plays by MusicHistoryFeature.plays.collectAsState()
  val last = remember(plays) { MusicHistory.listing(plays).firstOrNull() } ?: return null
  return { actions.playOne(last.toDataTrack(), MUSIC_RECENT_LABEL) }
}

/**
 * The screensaver must not start over a playing video (keep the screen awake and tell
 * [DreamPolicy]); leaving while playing pauses (the surface does that) and Home explains why once.
 */
@Composable
private fun VideoScreenGuards(now: MusicNowSlice, resumeOnOpen: Boolean) {
  val holds = now.holdsScreen
  if (holds) KeepInteractiveScreenAwake()
  DisposableEffect(holds) {
    YtVideoScreenState.playingOnScreen = holds
    onDispose { YtVideoScreenState.playingOnScreen = false }
  }
  val wasPlaying by rememberUpdatedState(now.playingOrWanted)
  DisposableEffect(Unit) {
    MusicV3FollowMeInScreen.screenShown(true)
    onDispose {
      MusicV3FollowMeInScreen.screenShown(false)
      if (wasPlaying) MusicV3Session.leftWhilePlaying()
    }
  }
  LaunchedEffect(Unit) {
    if (!resumeOnOpen) return@LaunchedEffect
    YouTubeMusicEngine.state.first { it.foreground }
    YouTubeMusicEngine.play()
  }
}

/** One toast per "Move to…" notice that arrives while this screen is open. */
@Composable
private fun MoveNotices(show: (String) -> Unit) {
  val notice by MusicHandoff.notice.collectAsState()
  val seenAtOpen = remember { MusicHandoff.notice.value?.seq ?: 0 }
  LaunchedEffect(notice?.seq) {
    notice?.takeIf { it.seq > seenAtOpen }?.let { show(it.text) }
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

/** "Music followed you from …" with Undo, as a stage toast (the overlay banner would cover the player). */
@Composable
private fun FollowNotices(toasts: MusicStageToasts) {
  val notice by MusicV3FollowMeInScreen.notice.collectAsState()
  LaunchedEffect(notice?.seq) {
    notice?.let { n -> toasts.show(n.text, action = "Undo" to { MusicV3FollowMeInScreen.undo(n) }) }
  }
}
