/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

/** Debug-only visual acceptance host. It never creates a player controller or audio output. */
class MusicDesignPreviewActivity : ComponentActivity() {
  private var nightClock: QuietNightClockView? = null
  private var gallery: SlideshowStage? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (intent.getStringExtra("screen") == "night") {
      window.attributes = window.attributes.apply { screenBrightness = 0.03f }
      nightClock = QuietNightClockView(this, intent.getStringExtra("tone") ?: "warm")
      setContentView(nightClock)
      return
    }
    if (intent.getStringExtra("screen") == "gallery") {
      gallery = SlideshowStage(this)
      setContentView(gallery)
      lifecycleScope.launch {
        val bitmap = withContext(Dispatchers.IO) {
          assets.open("music-design/window.png").use {
            android.graphics.BitmapFactory.decodeStream(it)
          }
        }
        bitmap?.let {
          gallery?.show(listOf(SlideshowPhoto("silent-print-preview", it)),
              fill = false, motion = false, dwell = 30_000, galleryPrint = true)
        }
      }
      return
    }
    setContent {
      val light = intent.getStringExtra("theme") == "light"
      val palette = if (light) ApprovedLightPalette else ApprovedDarkPalette
      val density = LocalDensity.current
      val fontScale = intent.getFloatExtra("font_scale", 1f).coerceIn(1f, 2f)
      val tracks by produceState<List<MusicQueueItem>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
          val names = listOf("dunes", "tidal", "window", "orbit", "field", "rose")
          val titles = listOf("After the sun", "Blue distance", "An open window",
              "Small hours", "The long way home", "Still, here")
          val artists = listOf("Sora Fields", "North Atlantic", "Eli & the City",
              "Low Orbit", "June Assembly", "Sora Fields")
          names.mapIndexed { index, name ->
            val file = File(cacheDir, "music-design/$name.png")
            file.parentFile?.mkdirs()
            assets.open("music-design/$name.png").use { input ->
              file.outputStream().use { input.copyTo(it) }
            }
            MusicQueueItem("sample-$index", titles[index], artists[index],
                240_000L + index * 17_000L, Uri.fromFile(file).toString())
          }
        }
      }
      var selected by remember { mutableIntStateOf(0) }
      var playing by remember { mutableStateOf(false) }
      var position by remember { mutableLongStateOf(83_000L) }
      var changedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
      var layout by remember { mutableStateOf(MusicPlayerLayout.FULL) }
      var message by remember { mutableStateOf("Silent design preview · sample music · no audio") }
      val connection = when (intent.getStringExtra("scenario")) {
        "offline" -> MusicConnection.OFFLINE
        "setup" -> MusicConnection.NOT_CONFIGURED
        else -> MusicConnection.READY
      }
      val source = "ma:design-preview"
      val item = tracks.getOrNull(selected)
      val snapshot = MusicPlayerSnapshot(connection = connection, sourceId = source,
          track = item?.let { MusicTrack(it.id, source, it.title, it.artist,
              "The quiet hours", it.artUrl, it.durationMs) },
          playback = if (playing) PlaybackState.PLAYING else PlaybackState.PAUSED,
          positionMs = position, positionAtElapsedMs = changedAt,
          capabilities = MusicCapabilities(play = true, pause = true, previous = true,
              next = true, seek = true, queue = true, rooms = true, volume = true),
          queue = tracks, queueId = "sample-queue",
          controlPlayerId = source,
          rooms = listOf(MusicRoom(source, "Family Area", true, 35,
              volumeControllable = true)))
      CompositionLocalProvider(LocalApprovedPalette provides palette,
          LocalDensity provides Density(density.density, fontScale)) {
        MaterialTheme(colorScheme = approvedColorScheme(!light)) {
          Column(Modifier.fillMaxSize().background(palette.background).padding(16.dp)) {
            Text(message, color = palette.secondary, modifier = Modifier.padding(8.dp))
            val playerModifier = if (layout == MusicPlayerLayout.COMPACT) Modifier.fillMaxWidth()
                else Modifier.weight(1f).fillMaxWidth()
            MusicPlayerContent(snapshot, layout, playerModifier,
                onDismiss = { finish() }, onLayoutChange = { layout = it },
                onAction = { action ->
                  message = "Silent design preview · sample music · no audio"
                  when (action) {
                    MusicPlayerUiAction.TogglePlayback -> {
                      position = snapshot.visiblePositionMs(SystemClock.elapsedRealtime()) ?: position
                      playing = !playing
                    }
                    MusicPlayerUiAction.Next -> {
                      if (tracks.isNotEmpty()) selected = (selected + 1) % tracks.size
                      position = 0
                    }
                    MusicPlayerUiAction.Previous -> {
                      if (tracks.isNotEmpty()) selected = (selected + tracks.size - 1) % tracks.size
                      position = 0
                    }
                    is MusicPlayerUiAction.SelectQueueItem -> {
                      selected = tracks.indexOfFirst { it.id == action.id }.coerceAtLeast(0)
                      position = 0
                      playing = false
                    }
                    is MusicPlayerUiAction.Seek -> position = action.positionMs
                    else -> message = "Design preview · provider controls are deferred · no audio"
                  }
                  changedAt = SystemClock.elapsedRealtime()
                },
                photoContent = { modifier ->
                  Box(modifier.background(palette.surface)) {
                    Text("Photo layout preview", color = palette.text,
                        modifier = Modifier.padding(24.dp))
                  }
                })
          }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    nightClock?.setActive(true)
    gallery?.setHostActive(true)
  }

  override fun onPause() {
    nightClock?.setActive(false)
    gallery?.setHostActive(false)
    super.onPause()
  }

  override fun onDestroy() {
    nightClock?.setActive(false)
    gallery?.stop()
    super.onDestroy()
  }
}
