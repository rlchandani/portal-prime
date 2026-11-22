/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Everything a room's tiles need from the screen. */
internal class HaRoomCallbacks(
    val run: (HaDashboardSession.Action) -> Unit,
    val media: (HaMediaAction) -> Unit,
    val retry: () -> Unit,
)

/** The room area: offline banner, room title + facts + "All off", then the tile grid (or its empty state). */
@Composable
internal fun ColumnScope.HaRoomMain(room: HaDashboardApi.Area, details: List<HaDashboardApi.Detail>,
    session: HaDashboardSession, offlineAge: String?, nowPlaying: NowPlayingState?, cb: HaRoomCallbacks) {
  if (offlineAge != null) HaOfflineBanner(offlineAge, cb.retry)
  HaRoomHead(room, details, offline = offlineAge != null) { cb.run(it) }
  val tiles = remember(details) { HaDashboardModel.tilesFor(details) }
  if (tiles.isEmpty()) {
    HaEmptyState(HaDashboardModel.roomIcon(room.id, room.name), "Nothing to control in ${room.name}",
        "Lights, switches, climate and sensors you assign to this area in Home Assistant appear here.")
    return
  }
  val stale = offlineAge != null
  HaGrid(tiles.map { HaDashboardModel.span(it.kind) }, Modifier.weight(1f)) {
    tiles.forEach { t ->
      val d = t.detail
      val failed = session.failedId == d.entityId
      val tap = { cb.run(HaDashboardSession.Action.Tap(d)) }
      when (t.kind) {
        HaDashboardModel.Kind.TOGGLE -> HaToggleTile(d, stale, failed, tap, Modifier)
        HaDashboardModel.Kind.DIMMER -> HaDimmerTile(d, stale, failed, tap,
            { pct -> cb.run(HaDashboardSession.Action.Dim(d, pct)) }, Modifier)
        HaDashboardModel.Kind.SENSOR -> HaSensorTile(d, session.history[d.entityId].orEmpty(), stale, Modifier)
        HaDashboardModel.Kind.CLIMATE -> HaClimateTile(d, stale, failed,
            { target -> cb.run(HaDashboardSession.Action.Temp(d, target)) }, Modifier)
        HaDashboardModel.Kind.MEDIA -> HaMediaTile(d, nowPlaying, stale, cb.media, Modifier)
      }
    }
  }
}

@Composable
private fun HaRoomHead(room: HaDashboardApi.Area, details: List<HaDashboardApi.Detail>, offline: Boolean,
    onAction: (HaDashboardSession.Action) -> Unit) {
  val d = LocalHaDims.current
  val c = LocalHaColors.current
  val lit = HaDashboardModel.litEntities(details)
  Row(Modifier.fillMaxWidth().height(d.target.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    HaText(room.name, d.roomTitle, c.text, Modifier.semantics { heading() }, serif = true, lineHeight = 1f)
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      if (details.isNotEmpty()) HaFact("bulb", if (lit.isEmpty()) "Lights off" else "${lit.size} on")
      HaDashboardModel.temperature(details)?.let { HaFact("therm", "${it.state}°") }
      HaDashboardModel.humidity(details)?.let { HaFact("drop", "${it.state}%") }
    }
    if (lit.isNotEmpty() && !offline) {
      HaPill("All off", { onAction(HaDashboardSession.Action.AllOff(room.name, lit)) }, icon = "power")
    }
  }
}

@Composable
private fun HaFact(icon: String, text: String) {
  val c = LocalHaColors.current
  Row(Modifier.height(44.dp).clip(HaShape.Pill).background(c.surface2).padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    HaIcon(icon, c.sec, 22.dp)
    HaText(text, LocalHaDims.current.small, c.text)
  }
}

/**
 * The dense tile grid: [HaDashboardGrid.pack] places each child (one per span, in order) on the
 * device's column count with one gap size and fixed row heights; the grid scrolls when it's taller.
 */
@Composable
internal fun HaGrid(spans: List<HaDashboardModel.Span>, modifier: Modifier, content: @Composable () -> Unit) {
  val d = LocalHaDims.current
  val packing = remember(spans, d.columns) { HaDashboardGrid.pack(spans, d.columns) }
  // Inset so a failed tile's 4 dp ring isn't clipped by the scroll edge.
  Box(modifier.fillMaxWidth().clip(androidx.compose.ui.graphics.RectangleShape).verticalScroll(rememberScrollState())
      .padding(GRID_INSET.dp)) {
    Layout(content) { measurables, constraints ->
      val gap = d.gap.dp.roundToPx()
      val rowH = d.rowHeight.dp.roundToPx()
      val colW = (constraints.maxWidth - gap * (d.columns - 1)) / d.columns
      val placed = measurables.zip(packing.placements).map { (m, p) ->
        val w = colW * p.cols + gap * (p.cols - 1)
        val h = rowH * p.rows + gap * (p.rows - 1)
        m.measure(Constraints.fixed(w, h)) to p
      }
      layout(constraints.maxWidth, HaDashboardGrid.height(packing.rowCount, rowH, gap)) {
        placed.forEach { (pl, p) -> pl.place(p.col * (colW + gap), p.row * (rowH + gap)) }
      }
    }
  }
}

private const val GRID_INSET = 2
