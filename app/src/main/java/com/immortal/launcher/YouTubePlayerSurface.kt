/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * The official YouTube player, embeddable in Compose, as one of its hosts ([MusicPlayerHostKind]).
 * The caller sizes it via [modifier]; it must be at least 200×200 dp/CSS px and nothing may be drawn
 * over it or its controls (YouTube RMF). Debug builds refuse playback while it is smaller (see
 * [YtPlayerSize]).
 *
 * The host counts as on screen while composed and its lifecycle is at least [activeFrom]. The ONE
 * player moves between hosts without reloading; when no host shows it the engine pauses it (no
 * background playback), nothing resumes without a user action, and it is released after
 * [YouTubeMusicEngine.RELEASE_GRACE_MS].
 *
 * [shown] = false keeps the one player (and its page) alive but hidden, for "nothing loaded" and
 * "playing in another room"; the caller also moves it out of the way so it never takes touches.
 */
@Composable
fun YouTubePlayerSurface(
    host: MusicPlayerHostKind,
    modifier: Modifier = Modifier,
    shown: Boolean = true,
    activeFrom: Lifecycle.State = Lifecycle.State.RESUMED,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, host, activeFrom) {
    val lifecycle = lifecycleOwner.lifecycle
    val observer = LifecycleEventObserver { _, event ->
      YouTubeMusicEngine.setActive(host, event.targetState.isAtLeast(activeFrom))
    }
    lifecycle.addObserver(observer)
    YouTubeMusicEngine.setActive(host, lifecycle.currentState.isAtLeast(activeFrom))
    onDispose {
      lifecycle.removeObserver(observer)
      YouTubeMusicEngine.setActive(host, false)
    }
  }
  AndroidView(
      factory = { ctx -> FrameLayout(ctx).also { YouTubeMusicEngine.attach(host, it) } },
      update = { it.visibility = if (shown) View.VISIBLE else View.INVISIBLE },
      onRelease = { YouTubeMusicEngine.detach(host, it) },
      modifier = modifier,
  )
}
