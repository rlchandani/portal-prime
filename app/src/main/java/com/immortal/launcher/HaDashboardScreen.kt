/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Calendar

/** Everything the screen draws, derived once per frame from the controller's two immutable values. */
internal data class HaDashboardView(
    val state: HaDashboardState,
    val session: HaDashboardSession,
    val greeting: String,
    val subline: String,
    val rooms: List<HaRoomEntry>,
    val room: HaDashboardApi.Area?,
    val roomDetails: List<HaDashboardApi.Detail>,
    val scenes: List<HaDashboardDay.SceneChip>,
    val offlineAge: String?,
)

/** Pure: the view for [state]/[session] at [nowMs] (clock in [use24h]). */
internal fun haDashboardView(state: HaDashboardState, session: HaDashboardSession, nowMs: Long, use24h: Boolean): HaDashboardView {
  val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
  val part = HaDashboardDay.partOf(cal.get(Calendar.HOUR_OF_DAY))
  val house = state.house
  val areas = house?.areas.orEmpty().map { it.copy(details = session.effective(it.details)) }
  val all = areas.flatMap { it.details }
  val scenes = house?.let { HaDashboardDay.scenes(it.scenes, part) }.orEmpty()
  val offline = state.offlineMinutes(nowMs)
  val subline = stateSubline(state) ?: HaDashboardDay.subline(
      HaDashboardDay.clock(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), use24h), part,
      HaDashboardDay.Facts(lit = HaDashboardModel.litCount(all), lock = HaDashboardDay.lockFact(all),
          insideTemp = HaDashboardModel.temperature(all)?.let { "${HaEntityLook.reading(it)}°" },
          offlineMinutes = offline, hasNightScene = scenes.any { HaDashboardDay.sceneWhen(it.name) == HaDashboardDay.Part.NIGHT }))
  val room = house?.let { session.room(it) }?.let { r -> areas.first { it.id == r.id } }
  return HaDashboardView(state, session, part.greeting, subline,
      rooms = areas.map { HaRoomEntry(it.id, it.name, HaDashboardModel.roomIcon(it.id, it.name),
          HaDashboardModel.litCount(it.details), HaDashboardModel.tilesFor(it.details).isNotEmpty()) },
      room = room, roomDetails = room?.details.orEmpty(), scenes = scenes,
      offlineAge = offline?.let { HaDashboardDay.ageText(it) })
}

/** Screen actions the host wires to the controller and the activity. */
internal class HaDashboardActions(
    val back: () -> Unit, val settings: () -> Unit, val retry: () -> Unit, val select: (String) -> Unit,
    val scene: (HaDashboardDay.SceneChip) -> Unit, val retryToast: () -> Unit, val room: HaRoomCallbacks,
)

@Composable
internal fun HaDashboardScreen(view: HaDashboardView, nowPlaying: NowPlayingState?, a: HaDashboardActions) {
  val d = LocalHaDims.current
  val c = LocalHaColors.current
  Box(Modifier.fillMaxSize().background(c.bg)) {
    Column(Modifier.fillMaxSize()) {
      HaHeader(view.greeting, view.subline, connectionOf(view.state), a.back, a.settings)
      if (view.state == HaDashboardState.NotConfigured) { HaSetupCard(a.settings, a.back); return@Column }
      val body = Modifier.fillMaxSize().padding(start = d.pad.dp, end = d.pad.dp, bottom = d.pad.dp)
      val main: @Composable ColumnScope.() -> Unit = { HaMain(view, nowPlaying, a) }
      val hasSide = view.state.house != null || view.state == HaDashboardState.Loading
      if (d.portrait) {
        Column(body, verticalArrangement = Arrangement.spacedBy(16.dp)) {
          if (hasSide) HaPortraitTop(view, a)
          Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp), content = main)
        }
      } else {
        Row(body, horizontalArrangement = Arrangement.spacedBy(d.sideGap.dp)) {
          if (hasSide) HaLandscapeSide(view, a)
          Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp), content = main)
        }
      }
    }
    val toast = view.session.toast
    AnimatedVisibility(toast != null, Modifier.align(Alignment.BottomCenter).padding(bottom = (d.pad + 6).dp),
        enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
      toast?.let { HaToast(it, a.retryToast, Modifier) }
    }
  }
}

@Composable
private fun HaLandscapeSide(view: HaDashboardView, a: HaDashboardActions) {
  val d = LocalHaDims.current
  if (view.state == HaDashboardState.Loading) {
    Column(Modifier.width(d.sideWidth.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      HaLabel("Rooms", Modifier.padding(start = 6.dp))
      HaSideSkeleton()
    }
    return
  }
  HaSideColumn(view.rooms, view.room?.id, view.scenes, view.session.firedScene, a.select, a.scene, Modifier)
}

@Composable
private fun ColumnScope.HaPortraitTop(view: HaDashboardView, a: HaDashboardActions) {
  if (view.state == HaDashboardState.Loading) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      repeat(PORTRAIT_SKELETON_CHIPS) { Box(Modifier.size(width = 170.dp, height = 64.dp).haSkeleton(22)) }
    }
    return
  }
  HaPortraitStrips(view.rooms, view.room?.id, view.scenes, view.session.firedScene, a.select, a.scene)
}

@Composable
private fun ColumnScope.HaMain(view: HaDashboardView, nowPlaying: NowPlayingState?, a: HaDashboardActions) {
  val settings = HaStateAction("Open settings", "gear", primary = true, onClick = a.settings)
  when (val s = view.state) {
    HaDashboardState.Loading -> HaGridSkeleton()
    HaDashboardState.Unauthorized -> HaEmptyState("key", "Home Assistant turned down the sign-in",
        "The access token has expired or was removed. Paste a new long-lived token in Settings and the dashboard " +
            "comes straight back.", red = true, actions = listOf(settings, HaStateAction("Try again", "refresh", false, a.retry)))
    HaDashboardState.NoRooms -> HaEmptyState("home", "No rooms yet",
        "Create areas in Home Assistant and assign lights, switches and sensors to them. They show up here on their own.",
        actions = listOf(HaStateAction("Check again", "refresh", false, a.retry)))
    is HaDashboardState.Unreachable -> HaEmptyState("wifioff", "Can't reach Home Assistant",
        (if (s.code != null) "Home Assistant answered with an error (HTTP ${s.code}). " else
            "This Portal got no answer from Home Assistant. ") + "Check the address in Settings, then try again.",
        actions = listOf(HaStateAction("Try again", "refresh", true, a.retry), settings.copy(primary = false)))
    HaDashboardState.NotConfigured -> Unit
    is HaDashboardState.Ready, is HaDashboardState.Offline -> view.room?.let {
      HaRoomMain(it, view.roomDetails, view.session, view.offlineAge, nowPlaying, a.room)
    }
  }
}

private const val PORTRAIT_SKELETON_CHIPS = 4
