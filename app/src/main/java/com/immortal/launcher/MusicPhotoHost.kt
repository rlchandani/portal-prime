/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** The real photo engine, hosted in-page without dream navigation or ambient audio. */
@Composable
internal fun MusicPhotoHost(modifier: Modifier) {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val frame = remember(context) {
    PhotoFrameController(context, interactivePreview = true, photosOnly = true)
  }
  AndroidView(modifier = modifier, factory = {
    frame.view.apply {
      setOnTouchListener { _, event ->
        if (!frame.dispatchViewerControls(event)) frame.onTouch(event)
        true
      }
    }
  })
  DisposableEffect(frame, owner) {
    frame.start()
    fun applyVisibility() {
      frame.setHostActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val observer = LifecycleEventObserver { _, _ -> applyVisibility() }
    owner.lifecycle.addObserver(observer)
    applyVisibility()
    onDispose {
      owner.lifecycle.removeObserver(observer)
      frame.stop()
    }
  }
}
