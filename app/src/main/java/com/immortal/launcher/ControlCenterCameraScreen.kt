/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A camera as the grid and strip need it. */
@Immutable
internal data class CcCamRef(val id: String, val name: String)

/** The grid's slice: equal (and skipped) until a camera is added, renamed or removed. */
@Immutable
internal data class CcCamGrid(val cameras: List<CcCamRef>)

/** The full view's slice: the camera, what is near it, the others for the strip. */
@Immutable
internal data class CcCamFull(val camera: CcEntity, val near: CcCameraLogic.Context, val others: CcCamGrid,
                              val canControl: Boolean, val reason: String?)

/** Cameras (`cams`): one large live tile and up to four snapshots; a tap opens the full view. */
@Composable
internal fun CcCamerasScreen(s: CcUiState, ui: CcUi) {
  val configured = s.connection.phase != CcPhase.NOT_CONFIGURED
  val all = remember(s.entitiesVersion, s.settings) { s.ofDomain("camera") }
  val grid = remember(all) { CcCamGrid(CcCameraLogic.gridCameras(all).map { CcCamRef(it.id, it.name) }) }
  val fullId = ui.cameraFull
  val full = remember(fullId, s.entitiesVersion, s.plan, s.connection.canControl, all) {
    fullId?.let { id -> s.entity(id)?.let { cam -> fullSlice(cam, s, all) } }
  }
  Box(Modifier.fillMaxSize()) {
    when {
      ccCamerasHidden(s.settings) -> CcCenteredMessage("smile", "Cameras are hidden", "Kids mode on this Portal hides the cameras.")
      !configured -> CcCenteredMessage("link", "Home Assistant isn't set up",
          "Connect Home Assistant in Settings › Home Assistant to see your cameras here.")
      all.isEmpty() && s.ready -> CcCenteredMessage("cam", "No cameras in Home Assistant",
          "Add a camera integration in Home Assistant (doorbell, Frigate, ONVIF, generic camera…). Cameras appear here on their own.")
      all.isEmpty() -> CcCenteredMessage("cam", "Loading cameras…", s.connection.step.ifBlank { "Connecting to Home Assistant" })
      full != null -> CcCameraFull(full, ui)
      else -> CameraGrid(grid, ui)
    }
  }
}

private fun fullSlice(cam: CcEntity, s: CcUiState, all: List<CcEntity>): CcCamFull = CcCamFull(cam,
    CcCameraLogic.context(cam.id, s.plan, s.entities), CcCamGrid(all.filter { it.id != cam.id }.take(CcCameraLogic.STRIP_MAX).map { CcCamRef(it.id, it.name) }),
    s.connection.canControl, s.connection.disabledReason)

/** `.camGrid`: columns 2fr/1fr, three rows, the first tile spanning two. */
@Composable
private fun CameraGrid(grid: CcCamGrid, ui: CcUi) {
  val gap = 20.dp
  val cameras = grid.cameras
  Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
    val large = cameras.first()
    val rest = cameras.drop(1)
    if (rest.isEmpty()) { GridTile(large, true, ui, Modifier.weight(1f).fillMaxHeight()); return@Row }
    Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
      GridTile(large, true, ui, Modifier.weight(2f).fillMaxWidth())
      if (rest.size >= BOTTOM_ROW_FROM) GridTile(rest[2], false, ui, Modifier.weight(1f).fillMaxWidth())
    }
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
      val right = if (rest.size >= BOTTOM_ROW_FROM) listOfNotNull(rest[0], rest[1], rest.getOrNull(3)) else rest
      right.forEach { key(it.id) { GridTile(it, false, ui, Modifier.weight(1f).fillMaxWidth()) } }
      if (rest.size == BOTTOM_ROW_FROM) Spacer(Modifier.weight(1f))
    }
  }
}

@Composable
private fun GridTile(cam: CcCamRef, live: Boolean, ui: CcUi, modifier: Modifier) {
  val open = remember(cam.id, ui) { { ui.camera(cam.id) } }
  CcCameraPicture(cam.id, cam.name, live, configured = true, modifier = modifier, onClick = open, size = CcCameraTileSize.GRID)
}

/** Four cameras or more fill the third row (the mock's 2+1 / 1 / 1+1 grid). */
private const val BOTTOM_ROW_FROM = 3

/** `.camFull`: the live picture, then a side column with shortcuts, recent activity and the others. */
@Composable
private fun CcCameraFull(f: CcCamFull, ui: CcUi) {
  val holder = remember(f.camera.id) { CcFrameHolder(f.camera.id) }
  Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    CcCameraPicture(f.camera.id, f.camera.name, live = true, configured = true, modifier = Modifier.weight(1f).fillMaxHeight(),
        onClick = null, size = CcCameraTileSize.FULL, holder = holder)
    Column(Modifier.width(420.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      CcCameraSideHead(f.camera, f.near, f.canControl, f.reason, ui, holder)
      CcCameraRecent(f.camera, f.near, Modifier.weight(1f).fillMaxWidth())
      OtherCameras(f.others, ui)
    }
  }
}

@Composable
private fun OtherCameras(strip: CcCamGrid, ui: CcUi) {
  val others = strip.cameras
  if (others.isEmpty()) return
  Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    others.forEach { e ->
      key(e.id) {
        val open = remember(e.id, ui) { { ui.camera(e.id) } }
        CcCameraPicture(e.id, e.name, live = false, configured = true, modifier = Modifier.weight(1f).fillMaxHeight(),
            onClick = open, size = CcCameraTileSize.SMALL)
      }
    }
    repeat(CcCameraLogic.STRIP_MAX - others.size) { Spacer(Modifier.weight(1f)) }
  }
}
