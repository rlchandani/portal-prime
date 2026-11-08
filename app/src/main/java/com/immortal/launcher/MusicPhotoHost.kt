/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Own photos at the Music host, not at a conditional layout mount. Creation stays lazy. */
@Composable
internal fun rememberMusicPhotos(visible: Boolean): MusicPhotoRetention<PhotoFrameController> {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val currentVisible by rememberUpdatedState(visible)
  var resumeEpoch by remember(owner) { mutableIntStateOf(0) }
  DisposableEffect(owner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) resumeEpoch++
    }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer) }
  }
  // Refresh effective photo inputs only on a layout/resume boundary, never on progress ticks.
  val guests by GuestMode.state.collectAsState()
  val restartKey = remember(context, visible, resumeEpoch, guests) {
    musicPhotoRestartKey(GuestPolicy.screensaver(ScreensaverConfig.load(context)))
  }
  val retained = remember(context, owner, restartKey) {
    MusicPhotoRetention(
        create = { PhotoFrameController(context, interactivePreview = true, photosOnly = true) },
        start = { it.start() }, setActive = { frame, active -> frame.setHostActive(active) },
        stop = { it.stop() })
  }
  fun applyVisibility() {
    retained.setVisible(currentVisible && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
  }
  DisposableEffect(retained, owner) {
    val observer = LifecycleEventObserver { _, _ -> applyVisibility() }
    owner.lifecycle.addObserver(observer)
    applyVisibility()
    onDispose {
      owner.lifecycle.removeObserver(observer)
      retained.close()
    }
  }
  SideEffect { applyVisibility() }
  return retained
}

/** Remount the retained real photo view without restarting its source/decode pipeline. */
@Composable
internal fun MusicPhotoHost(retained: MusicPhotoRetention<PhotoFrameController>, modifier: Modifier) {
  key(retained) {
    val frame = remember(retained) { retained.frame() }
    AndroidView(modifier = modifier, factory = {
      frame.view.apply {
        setOnTouchListener { _, event ->
          if (!frame.dispatchViewerControls(event)) frame.onTouch(event)
          true
        }
      }
    })
    DisposableEffect(retained) {
      retained.start()
      onDispose { retained.setVisible(false) }
    }
  }
}
