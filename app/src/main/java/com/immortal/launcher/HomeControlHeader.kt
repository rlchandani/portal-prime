/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.components.OnResume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The Home header's Home Control status dot. */
internal enum class HomeControlDot { NONE, AMBER, RED }

/**
 * What the dot says (pure, unit tested): red for a critical alert (leak, smoke, CO), amber when
 * something needs attention (an alert, a garage or door open, a lock unlocked), none when all is
 * fine. Reads the engine's state; never asks HA for anything itself.
 */
internal object HomeControlStatus {
  private val OPEN_KINDS = setOf(CcKind.GARAGE, CcKind.DOOR, CcKind.LOCK)

  fun dot(s: CcUiState): HomeControlDot {
    if (s.connection.phase == CcPhase.NOT_CONFIGURED) return HomeControlDot.NONE
    val alerts = s.alerts()
    if (alerts.any { it.severity == CcSeverity.CRITICAL }) return HomeControlDot.RED
    if (alerts.any { it.severity == CcSeverity.WARNING }) return HomeControlDot.AMBER
    val open = s.entities.values.any { e -> !e.unavailable && CcKind.of(e) in OPEN_KINDS && e.isOpen && !CcPolicy.hidden(e, s.settings) }
    return if (open) HomeControlDot.AMBER else HomeControlDot.NONE
  }

  /**
   * Off screen the engine keeps only the banner subscription, so doors and locks drop out of its
   * state; the header then shows the stronger of what is still known now and the last full view.
   */
  fun combine(now: HomeControlDot, lastForeground: HomeControlDot): HomeControlDot = maxOf(now, lastForeground)

  /** The button shows when HA is set up and this Portal isn't banners-only. */
  fun shown(haConfigured: Boolean, settings: ControlCenterConfig.Settings): Boolean = haConfigured && !settings.bannersOnly
}

/** The engine's dot as the header reads it (set on the engine thread in [CcEngine.publish]). */
internal object HomeControlDotStore {
  private val dot = MutableStateFlow(HomeControlDot.NONE)
  val flow: StateFlow<HomeControlDot> get() = dot
  @Volatile private var lastForeground = HomeControlDot.NONE

  fun update(s: CcUiState, visible: Boolean) {
    val now = HomeControlStatus.dot(s)
    if (visible) lastForeground = now
    val next = if (visible) now else HomeControlStatus.combine(now, lastForeground)
    if (next != dot.value) android.util.Log.i("HearthpaneControl", "home control dot: ${next.name.lowercase()}")
    dot.value = next
  }
}

/** Whether the header has the Home Control button; re-read whenever Home resumes. */
@Composable
internal fun rememberHomeControlShown(context: Context = LocalContext.current): Boolean {
  val shown = remember { mutableStateOf(homeControlShown(context)) }
  OnResume { shown.value = homeControlShown(context) }
  return shown.value
}

private fun homeControlShown(context: Context): Boolean =
    HomeControlStatus.shown(HaConfig.isConfigured(context), ControlCenterConfig.load(context))

/** A 68 dp header button like its neighbours, with the status dot at its top-right. */
@Composable
internal fun HomeControlHeaderButton() {
  val context = LocalContext.current
  val dot by HomeControlDotStore.flow.collectAsState()
  Box(Modifier.size(68.dp)) {
    Box(Modifier.size(68.dp).clip(CircleShape).background(ApprovedHomePalette.Chrome).approvedOutline(CircleShape)
        .semantics { contentDescription = "Home Control" }
        .tvFocusable(CircleShape, ringColor = ApprovedHomePalette.Accent) { ControlCenterActivity.open(context) },
        contentAlignment = Alignment.Center) {
      ApprovedHomeIcon("homeassistant", tint = ApprovedHomePalette.Text)
    }
    if (dot != HomeControlDot.NONE) Box(Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 4.dp).size(DOT.dp).clip(CircleShape)
        .background(ApprovedHomePalette.Chrome).padding(DOT_RING.dp).clip(CircleShape).background(if (dot == HomeControlDot.RED) RED else AMBER))
  }
}

private const val DOT = 18
private const val DOT_RING = 3
private val AMBER = Color(0xFFF2A93B)
private val RED = Color(0xFFE5484D)
