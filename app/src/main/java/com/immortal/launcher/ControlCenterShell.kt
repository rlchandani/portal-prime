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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext

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
  BackHandler { if (!ui.handleBack()) onExit() }
  LaunchedEffect(requested) { requested?.let { ui.go(it.first) } }
  CcTheme(dark = state.settings.darkTheme) {
    CompositionLocalProvider(LocalCcMotion provides (motion && !state.settings.reducedMotion)) {
      val c = LocalCc.current
      Box(Modifier.fillMaxSize().background(c.bg)) {
        if (state.settings.bannersOnly) CcBannersOnly(onExit)
        else {
          Row(Modifier.fillMaxSize()) {
            CcRail(state, ui, onHome = onExit)
            Column(Modifier.weight(1f).fillMaxSize()) {
              val nav = navState.value
              CcHeader(state, ccTitle(nav.screen), subtitleFor(state, nav), ui) { JarvisHandoff.launch(context) }
              Box(Modifier.weight(1f).fillMaxWidth()) { CcScreenBody(state, ui) }
            }
          }
          CcSheetHost(state, ui)
          CcConnectionOverlay(state, ui)
          CcModalHost(state, ui)
          CcToastHost(state)
        }
      }
    }
  }
}

/** False for a kept-alive screen that isn't showing: cameras stop polling, nothing animates. */
internal val LocalCcScreenActive = compositionLocalOf { true }

/** Screens kept composed after the first visit so switching back costs no composition or redraw. */
private val KEEP_ALIVE = setOf(CcScreen.MAP, CcScreen.CAMERAS, CcScreen.ENERGY, CcScreen.ALERTS, CcScreen.SECURITY)

/**
 * The current screen, plus every kept-alive screen already visited, parked off-screen with the
 * snapshot it last showed (same instance, so it skips recomposition and keeps its draw lists).
 */
@Composable
private fun CcScreenBody(s: CcUiState, ui: CcUi) {
  val current = ui.nav.screen
  val visited = remember { LinkedHashSet<CcScreen>() }
  val frozen = remember { HashMap<CcScreen, CcUiState>() }
  if (current in KEEP_ALIVE) { visited += current; frozen[current] = s }
  val motion = LocalCcMotion.current
  Box(Modifier.fillMaxSize().clipToBounds()) {
    visited.forEach { screen ->
      key(screen) {
        val active = screen == current
        val parked = if (active) Modifier.fillMaxSize() else Modifier.fillMaxSize().offset { IntOffset(PARK_OFFSET_PX, 0) }
        Box(parked.graphicsLayer()) {
          CompositionLocalProvider(LocalCcScreenActive provides active, LocalCcMotion provides (active && motion)) {
            CcScreenContent(screen, if (active) s else frozen.getValue(screen), ui)
          }
        }
      }
    }
    if (current !in KEEP_ALIVE) CcScreenContent(current, s, ui)
  }
}

private const val PARK_OFFSET_PX = 100_000

@Composable
private fun CcScreenContent(screen: CcScreen, s: CcUiState, ui: CcUi) {
  when (screen) {
    CcScreen.MAP -> CcOverviewScreen(s, ui)
    CcScreen.CAMERAS -> CcCamerasScreen(s, ui)
    CcScreen.ENERGY -> CcEnergyScreen(s, ui)
    CcScreen.ALERTS -> CcAlertsScreen(s, ui)
    CcScreen.SECURITY -> CcSecurityScreen(s, ui)
    CcScreen.EDIT -> CcEditorScreen(s, ui)
    CcScreen.SETTINGS -> CcSettingsScreen(s, ui)
  }
}

internal fun ccTitle(screen: CcScreen): String = when (screen) {
  CcScreen.MAP -> "Home"
  CcScreen.CAMERAS -> "Cameras"
  CcScreen.ENERGY -> "Energy"
  CcScreen.ALERTS -> "Alerts"
  CcScreen.SECURITY -> "Security"
  CcScreen.EDIT -> "Edit floor plan"
  CcScreen.SETTINGS -> "Home settings"
}

private fun subtitleFor(s: CcUiState, nav: CcNav): String {
  if (nav.screen != CcScreen.MAP) return ccSubtitle(s.now).replace(Regex(" (morning|afternoon|evening|night)"), "")
  val floor = s.plan?.floor(nav.floorId)?.name
  val kids = if (s.settings.kids) "Kids mode" else null
  return ccSubtitle(s.now, listOfNotNull(floor, kids).joinToString(" · ").ifBlank { null })
}

@Composable
private fun BoxScope.CcBannersOnly(onExit: () -> Unit) {
  CcCenteredMessage(icon = "bell", title = "Banners only on this Portal",
      body = "This Portal shows Home Assistant alerts as banners. Change it in Settings › Home Assistant › Control Center.",
      primary = "Back" to onExit)
}
