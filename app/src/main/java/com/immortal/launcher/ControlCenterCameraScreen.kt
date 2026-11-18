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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Cameras (`cams`): one large live tile and up to four snapshots; a tap opens the full view. */
@Composable
internal fun CcCamerasScreen(s: CcUiState, ui: CcUi) {
  val hidden = s.settings.kids && CcPolicy.hiddenKind(CcKind.CAMERA, null, s.settings)
  val all = s.ofDomain("camera")
  val cameras = remember(all) { CcCameraLogic.gridCameras(all) }
  Box(Modifier.fillMaxSize()) {
    when {
      hidden -> CcCenteredMessage("smile", "Cameras are hidden", "Kids mode on this Portal hides the cameras.")
      s.connection.phase == CcPhase.NOT_CONFIGURED -> CcCenteredMessage("link", "Home Assistant isn't set up",
          "Connect Home Assistant in Settings › Home Assistant to see your cameras here.")
      all.isEmpty() && s.ready -> CcCenteredMessage("cam", "No cameras in Home Assistant",
          "Add a camera integration in Home Assistant (doorbell, Frigate, ONVIF, generic camera…). Cameras appear here on their own.")
      all.isEmpty() -> CcCenteredMessage("cam", "Loading cameras…", s.connection.step.ifBlank { "Connecting to Home Assistant" })
      ui.nav.cameraFull != null && s.entity(ui.nav.cameraFull!!) != null -> CcCameraFull(s, ui, ui.nav.cameraFull!!, all)
      else -> CameraGrid(s, ui, cameras)
    }
  }
}

/** `.camGrid`: columns 2fr/1fr, three rows, the first tile spanning two. */
@Composable
private fun CameraGrid(s: CcUiState, ui: CcUi, cameras: List<CcEntity>) {
  val gap = 20.dp
  val tile: @Composable (CcEntity, Boolean, Modifier) -> Unit = { e, live, m -> CcCameraTile(s, e.id, live, m, onClick = { ui.camera(e.id) }) }
  Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
    val large = cameras.first()
    val rest = cameras.drop(1)
    if (rest.isEmpty()) { tile(large, true, Modifier.weight(1f).fillMaxHeight()); return@Row }
    Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
      tile(large, true, Modifier.weight(2f).fillMaxWidth())
      if (rest.size >= BOTTOM_ROW_FROM) tile(rest[2], false, Modifier.weight(1f).fillMaxWidth())
    }
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
      val right = if (rest.size >= BOTTOM_ROW_FROM) listOfNotNull(rest[0], rest[1], rest.getOrNull(3)) else rest
      right.forEach { tile(it, false, Modifier.weight(1f).fillMaxWidth()) }
      if (rest.size == BOTTOM_ROW_FROM) Spacer(Modifier.weight(1f))
    }
  }
}

/** Four cameras or more fill the third row (the mock's 2+1 / 1 / 1+1 grid). */
private const val BOTTOM_ROW_FROM = 3

/** `.camFull`: the live picture, then a side column with shortcuts, recent activity and the others. */
@Composable
private fun CcCameraFull(s: CcUiState, ui: CcUi, cameraId: String, all: List<CcEntity>) {
  val camera = s.entity(cameraId) ?: return
  Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    CcCameraTileSized(s, cameraId, live = true, modifier = Modifier.weight(1f).fillMaxHeight(), label = camera.name,
        onClick = null, size = CcCameraTileSize.FULL)
    Column(Modifier.width(420.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      CcCameraSideHead(s, ui, camera)
      CcCameraRecent(s, camera, Modifier.weight(1f).fillMaxWidth())
      OtherCameras(s, ui, all.filter { it.id != cameraId }.take(CcCameraLogic.STRIP_MAX))
    }
  }
}

@Composable
private fun OtherCameras(s: CcUiState, ui: CcUi, others: List<CcEntity>) {
  if (others.isEmpty()) return
  Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    others.forEach { e ->
      CcCameraTileSized(s, e.id, live = false, modifier = Modifier.weight(1f).fillMaxHeight(), label = e.name,
          onClick = { ui.camera(e.id) }, size = CcCameraTileSize.SMALL)
    }
    repeat(CcCameraLogic.STRIP_MAX - others.size) { Spacer(Modifier.weight(1f)) }
  }
}
