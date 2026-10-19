/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * One passive local source subscription across all layouts. The controller never owns
 * audio focus or starts/resumes playback on attach, detach, or presentation changes.
 */
internal class MusicPlayerController {
  private val main = Handler(Looper.getMainLooper())
  val store = MusicPlayerStore()
  private var attached = false
  private var source = ""
  private var generation = 0L
  private var requestId = 0L
  private val listener = NowPlayingHub.Listener { _ ->
    main.post {
      if (attached) {
        val next = musicSessionSnapshot(NowPlayingHub.current, SystemClock.elapsedRealtime())
        if (next.sourceId != source) {
          source = next.sourceId
          generation = store.selectSource(source)
        }
        store.publish(generation, next)
      }
    }
  }

  fun attach() {
    if (attached) return
    attached = true
    NowPlayingHub.addListener(listener)
  }

  fun detach() {
    if (!attached) return
    attached = false
    NowPlayingHub.removeListener(listener)
    main.removeCallbacksAndMessages(null)
    source = ""
    generation = store.selectSource("")
  }

  fun send(action: MusicPlayerUiAction) {
    val snapshot = store.current
    val track = snapshot.track ?: return
    if (!snapshot.sourceId.startsWith("local:")) return
    val command: MusicCommand
    val confirmation: MusicConfirmation
    var position = 0L
    when (action) {
      MusicPlayerUiAction.TogglePlayback -> {
        val pause = snapshot.playback == PlaybackState.PLAYING
        command = if (pause) MusicCommand.PAUSE else MusicCommand.PLAY
        confirmation = MusicConfirmation.Playback(if (pause) PlaybackState.PAUSED else PlaybackState.PLAYING)
      }
      MusicPlayerUiAction.Next -> {
        command = MusicCommand.NEXT
        confirmation = MusicConfirmation.TrackChanged(track.id)
      }
      MusicPlayerUiAction.Previous -> {
        command = MusicCommand.PREVIOUS
        confirmation = MusicConfirmation.TrackChanged(track.id)
      }
      is MusicPlayerUiAction.Seek -> {
        val duration = track.durationMs ?: return
        if (action.positionMs !in 0..duration) return
        command = MusicCommand.SEEK
        position = action.positionMs
        confirmation = MusicConfirmation.Seek(track.id, position)
      }
      else -> return
    }
    val id = ++requestId
    val token = generation
    if (!store.beginCommand(id, command, SystemClock.elapsedRealtime(), confirmation)) return
    MediaSessionReader.musicCommand(snapshot.sourceId.removePrefix("local:"), snapshot.localSessionToken,
        track.id, command, position) { ok ->
      main.post {
        if (attached) {
          if (ok) store.acknowledge(token, id)
          else store.reject(token, id, "This player could not apply the change.")
        }
      }
    }
  }
}

/** Collect here so playback updates do not invalidate Home's widget/layout composition. */
@Composable
internal fun MusicPlayerHost(
    layout: MusicPlayerLayout,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onLayoutChange: (MusicPlayerLayout) -> Unit,
) {
  val context = LocalContext.current
  val controller = remember { MusicPlayerController() }
  DisposableEffect(controller) {
    controller.attach()
    onDispose { controller.detach() }
  }
  val snapshot by controller.store.state.collectAsState()
  LaunchedEffect(snapshot.pending?.requestId) {
    if (snapshot.pending != null) {
      delay(5_000)
      controller.store.expirePending(SystemClock.elapsedRealtime())
    }
  }
  MusicPlayerContent(snapshot, layout, modifier, onDismiss, onLayoutChange,
      onAction = { action ->
        when (action) {
          MusicPlayerUiAction.OpenSettings -> context.openMusicSettings()
          // Library browsing is only offered by an authenticated MA source adapter.
          MusicPlayerUiAction.BrowseLibrary -> Unit
          else -> controller.send(action)
        }
      },
      photoContent = { photoModifier -> MusicPhotoHost(photoModifier) })
}

private fun Context.openMusicSettings() {
  FramePerformanceCapture.launchSettings(this,
      Intent(this, ImmortalSettingsActivity::class.java)
          .putExtra(APPROVED_SETTINGS_CATEGORY, "connections")
          .putExtra(APPROVED_SETTINGS_PAGE, "multiroom"))
}
