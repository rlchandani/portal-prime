/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.jarvis.JarvisHighlight
import com.immortal.launcher.jarvis.ScreenContext
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.collectLatest

/**
 * True while ambient animation may run (resumed, screen on, no dream, motion not reduced).
 * Every looping animation in the Control Center must check it and draw a still frame otherwise.
 */
internal val LocalCcMotion = compositionLocalOf { false }

/** The whole Control Center: rail + header + the current screen, the sheet, modals and toasts. */
@Composable
internal fun CcApp(motion: Boolean, initialScreen: CcScreen = CcScreen.MAP, requested: Pair<CcScreen, Long>? = null, onExit: () -> Unit) {
  val context = LocalContext.current
  val state by ControlCenter.state(context).collectAsState()
  val latest by rememberUpdatedState(state)
  val navState = remember { mutableStateOf(CcNav(screen = initialScreen)) }
  val ui = remember { CcUi(context, navState, { latest }) }
  val onJarvis = remember { { JarvisHandoff.launch(context); Unit } }
  BackHandler { if (!ui.handleBack()) onExit() }
  LaunchedEffect(requested) { requested?.let { ui.go(it.first) } }
  LaunchedEffect(Unit) {
    JarvisHighlight.current.collect { request ->
      val target = request?.target ?: return@collect
      val plan = latest.plan ?: return@collect
      val roomId = when {
        target.startsWith(ScreenContext.ROOM_TARGET) -> target.removePrefix(ScreenContext.ROOM_TARGET)
        target.startsWith(ScreenContext.ENTITY_TARGET) -> plan.roomOf(target.removePrefix(ScreenContext.ENTITY_TARGET))?.id
        else -> null
      } ?: return@collect
      ui.pointAt(roomId, plan.floors.firstOrNull { f -> f.rooms.any { it.id == roomId } }?.id)
      kotlinx.coroutines.delay(JarvisHighlight.SHOW_MS)
      ui.stopPointing(roomId)
      JarvisHighlight.done(request)
    }
  }
  CcJarvisHomeEffects(ui) { latest }
  val layout = currentLayoutClass()
  // One body moved between the portrait and landscape frames, so rotating keeps the screens' state.
  val body = remember(ui) { movableContentOf { s: CcUiState -> CcScreenBody(s, ui) } }
  CcTheme(dark = state.settings.darkTheme) {
    CompositionLocalProvider(LocalCcMotion provides (motion && !state.settings.reducedMotion)) {
      val c = LocalCc.current
      Box(Modifier.fillMaxSize().background(c.bg).ccObserveTouches(ui)) {
        if (state.settings.bannersOnly) CcBannersOnly(onExit)
        else {
          if (layout.compactPortrait) Column(Modifier.fillMaxSize()) {
            // The Mini upright: two-row header on top, the screen, the bottom bar instead of the rail.
            val screen = ui.screen
            val floorId = ui.floorId
            val subtitle = remember(state.minute, screen, floorId, state.plan, state.settings.kids) { subtitleFor(state, screen, floorId) }
            val header = CcHeaderSlice.of(state, ccTitle(screen, state.registry?.tesla?.label ?: CcTeslaIds.DEFAULT_LABEL), subtitle)
            CcCompactHeader(header, ui, onJarvis)
            Box(Modifier.weight(1f).fillMaxWidth()) { body(state) }
            CcCompactNavBar(CcRailSlice.of(state, screen), ui, onHome = onExit)
          } else Row(Modifier.fillMaxSize()) {
            val screen = ui.screen
            val rail = CcRailSlice.of(state, screen)
            CcRail(rail, ui, onHome = onExit)
            Column(Modifier.weight(1f).fillMaxSize()) {
              val floorId = ui.floorId
              // Formatted once per minute / screen / floor, not on every HA update.
              val subtitle = remember(state.minute, screen, floorId, state.plan, state.settings.kids) { subtitleFor(state, screen, floorId) }
              val header = CcHeaderSlice.of(state, ccTitle(screen, state.registry?.tesla?.label ?: CcTeslaIds.DEFAULT_LABEL), subtitle)
              CcHeader(header, ui, onJarvis)
              Box(Modifier.weight(1f).fillMaxWidth()) { body(state) }
            }
          }
          CcSheetHost(state, ui)
          CcConnectionOverlay(state, ui)
          CcModalHost(state, ui)
          CcToastHost(state.toast)
        }
      }
    }
  }
}

/** False for a kept-alive screen that isn't showing: cameras stop polling, nothing animates. */
internal val LocalCcScreenActive = compositionLocalOf { true }

/** The current screen plus the kept-alive ones (see [CcScreenSlots]). */
@Composable
private fun CcScreenBody(s: CcUiState, ui: CcUi) {
  CcScreenSlots(s, ui) { screen, state -> CcScreenContent(screen, state, ui) }
}

@Composable
private fun CcScreenContent(screen: CcScreen, s: CcUiState, ui: CcUi) {
  when (screen) {
    CcScreen.MAP -> CcOverviewScreen(s, ui)
    CcScreen.CAMERAS -> CcCamerasScreen(s, ui)
    CcScreen.TESLA -> CcTeslaScreen(s, ui)
    CcScreen.ALERTS -> CcAlertsScreen(s, ui)
    CcScreen.SECURITY -> CcSecurityScreen(s, ui)
    CcScreen.EDIT -> CcEditorScreen(s, ui)
    CcScreen.SETTINGS -> CcSettingsScreen(s, ui)
  }
}

/** The header title; the Tesla screen is called by the car's HA name ([car]). */
internal fun ccTitle(screen: CcScreen, car: String = CcTeslaIds.DEFAULT_LABEL): String = when (screen) {
  CcScreen.MAP -> "Home"
  CcScreen.CAMERAS -> "Cameras"
  CcScreen.TESLA -> car
  CcScreen.ALERTS -> "Alerts"
  CcScreen.SECURITY -> "Security"
  CcScreen.EDIT -> "Edit floor plan"
  CcScreen.SETTINGS -> "Home settings"
}

private fun subtitleFor(s: CcUiState, screen: CcScreen, floorId: String?): String {
  if (screen != CcScreen.MAP) return ccSubtitle(s.now).replace(DAY_PART, "")
  val floor = s.plan?.floor(floorId)?.name
  val kids = if (s.settings.kids) "Kids mode" else null
  return ccSubtitle(s.now, listOfNotNull(floor, kids).joinToString(" · ").ifBlank { null })
}

@Composable
private fun BoxScope.CcBannersOnly(onExit: () -> Unit) {
  CcCenteredMessage(icon = "bell", title = "Banners only on this Portal",
      body = "This Portal shows Home Assistant alerts as banners. Change it in Settings › Home Assistant › Control Center.",
      primary = "Back" to onExit)
}

private val DAY_PART = Regex(" (morning|afternoon|evening|night)")

/** Notes every touch (without consuming it) so the prewarm only works while nobody is touching. */
private fun Modifier.ccObserveTouches(ui: CcUi): Modifier = pointerInput(ui) {
  awaitPointerEventScope {
    while (true) {
      awaitPointerEvent(PointerEventPass.Initial)
      ui.touched(android.os.SystemClock.uptimeMillis())
    }
  }
}

/**
 * `hearthpane.home_control` on this screen: the rooms Jarvis just changed pulse on the plan (their
 * lights fade on the canvas as the state arrives), and an asking-first request opens the Portal's
 * own confirm sheet over its room. A sheet asked for more than [CcJarvisHome.CONFIRM_TTL_MS] ago is
 * dropped, so an old request never pops up later.
 */
@Composable
private fun CcJarvisHomeEffects(ui: CcUi, state: () -> CcUiState) {
  LaunchedEffect(Unit) {
    CcJarvisHome.flash.collectLatest { f ->
      f ?: return@collectLatest
      val room = f.roomIds.singleOrNull()
      try {
        if (room != null) ui.pointAt(room, f.floorId) else f.floorId?.let { if (ui.floorId != it) ui.floor(it) }
        kotlinx.coroutines.delay(com.immortal.launcher.jarvis.JarvisHighlight.SHOW_MS)
      } finally {
        room?.let(ui::stopPointing)
        CcJarvisHome.flashDone(f)
      }
    }
  }
  LaunchedEffect(Unit) {
    CcJarvisHome.confirm.collect { c ->
      c ?: return@collect
      CcJarvisHome.confirmShown(c)
      if (!c.fresh(System.currentTimeMillis())) return@collect
      val e = state().entity(c.entityId) ?: return@collect
      c.roomId?.let { ui.pointAt(it, c.floorId) }
      ui.askFirst(e, c.call)
    }
  }
}
