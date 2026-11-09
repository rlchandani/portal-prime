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
 * The official YouTube player, embeddable in Compose. The caller sizes it via [modifier]; it must
 * be at least 200×200 dp/CSS px and nothing may be drawn over it or its controls (YouTube RMF).
 * Debug builds refuse playback while it is smaller (see [YtPlayerSize]).
 *
 * While composed and the host lifecycle is RESUMED the engine may play; ON_PAUSE or leaving
 * composition pauses it (no background playback) and nothing resumes without a user action.
 * Leaving composition also schedules the player's release ([YouTubeMusicEngine.RELEASE_GRACE_MS]).
 *
 * [shown] = false keeps the one player (and its page) alive but hidden, for "nothing loaded" and
 * "playing in another room"; the caller also moves it out of the way so it never takes touches.
 */
@Composable
fun YouTubePlayerSurface(modifier: Modifier = Modifier, shown: Boolean = true) {
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> YouTubeMusicEngine.setForeground(true)
        Lifecycle.Event.ON_PAUSE -> YouTubeMusicEngine.setForeground(false)
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
      YouTubeMusicEngine.setForeground(true)
    }
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      YouTubeMusicEngine.setForeground(false)
    }
  }
  AndroidView(
      factory = { ctx -> FrameLayout(ctx).also(YouTubeMusicEngine::attach) },
      update = { it.visibility = if (shown) View.VISIBLE else View.INVISIBLE },
      onRelease = YouTubeMusicEngine::detach,
      modifier = modifier,
  )
}
