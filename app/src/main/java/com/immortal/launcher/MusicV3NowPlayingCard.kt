/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** The official player tile is 736×414 dp in the mock (828×466 device px, above YouTube's 200×200). */
private const val PLAYER_ASPECT = 736f / 414f
private const val TICK_MS = 1_000L

/**
 * Left card: the YouTube player at the top (nothing is ever drawn over it while it has an item),
 * then our metadata, progress, transport and Portal volume below it.
 */
@Composable
internal fun MusicV3NowPlayingCard(engine: YtEngineState, room: String, modifier: Modifier = Modifier) {
  val model = MusicV3NowPlaying.from(engine, room)
  Column(modifier.clip(RoundedCornerShape(MusicV3Dims.PaneRadius)).background(ApprovedHomePalette.Card)
      .padding(32.dp)) {
    PlayerTile(model.hasTrack)
    Meta(model)
    Spacer(Modifier.weight(1f))
    model.halt?.let { HaltNote(it) }
    when {
      model.isAd -> Text(MusicV3NowPlaying.AD_NOTE, color = ApprovedHomePalette.Secondary, fontSize = 23.sp,
          lineHeight = 32.sp, modifier = Modifier.padding(bottom = 12.dp))
      model.showProgress -> Progress(engine)
    }
    if (model.showBigPlay) BigPlay() else Transport(model)
    MusicV3VolumeRow(room)
  }
}

@Composable
private fun PlayerTile(hasTrack: Boolean) {
  Box(Modifier.fillMaxWidth().aspectRatio(PLAYER_ASPECT).clip(RoundedCornerShape(20.dp))
      .background(ApprovedHomePalette.Chrome)) {
    YouTubePlayerSurface(Modifier.fillMaxSize())
    // Only while the queue is empty (the player has no video to show) does anything sit here.
    if (!hasTrack) IdleTile()
  }
}

@Composable
private fun IdleTile() {
  Column(Modifier.fillMaxSize().background(ApprovedHomePalette.Chrome), verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally) {
    ApprovedIcon("music", Modifier.size(64.dp), ApprovedHomePalette.Secondary)
    Text("Nothing playing", color = ApprovedHomePalette.Text, fontSize = 30.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 16.dp))
    Text("Choose a playlist or search for a song.", color = ApprovedHomePalette.Secondary, fontSize = 23.sp,
        modifier = Modifier.padding(top = 8.dp))
  }
}

@Composable
private fun Meta(model: MusicV3NowPlaying) {
  val track = model.track ?: return
  Column(Modifier.padding(top = 36.dp)) {
    if (model.isAd) AdLine() else {
      Text(model.statusLine, color = ApprovedHomePalette.Secondary, fontSize = 23.sp, maxLines = 1)
    }
    Text(track.title, color = ApprovedHomePalette.Text, fontSize = 44.sp, lineHeight = 52.sp,
        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp))
    if (track.artist.isNotBlank()) {
      Text(track.artist, color = ApprovedHomePalette.Secondary, fontSize = 28.sp, lineHeight = 36.sp,
          maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
  }
}

@Composable
private fun AdLine() {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Ad", color = ApprovedHomePalette.Card, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(ApprovedHomePalette.Text)
            .padding(horizontal = 12.dp, vertical = 2.dp))
    Text(MusicV3NowPlaying.AD_LINE, color = ApprovedHomePalette.Secondary, fontSize = 23.sp)
  }
}

@Composable
private fun HaltNote(message: MusicV3Message) {
  Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(20.dp))
      .background(ApprovedHomePalette.Chrome).padding(horizontal = 24.dp, vertical = 16.dp)) {
    Text(message.title, color = ApprovedHomePalette.Text, fontSize = 25.sp, fontWeight = FontWeight.Medium)
    Text(message.detail, color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
  }
}

@Composable
private fun Progress(engine: YtEngineState) {
  val playing = engine.status == YtPlayerStatus.PLAYING
  val now by produceState(SystemClock.elapsedRealtime(), playing, engine.positionAtElapsedMs) {
    value = SystemClock.elapsedRealtime()
    while (playing) { delay(TICK_MS); value = SystemClock.elapsedRealtime() }
  }
  val length = MusicV3NowPlaying.durationMs(engine)
  val live = MusicV3NowPlaying.livePositionMs(engine, now)
  var drag by remember(engine.queue.current?.id) { mutableStateOf<Float?>(null) }
  val fraction = drag ?: if (length > 0) live.toFloat() / length else 0f
  Column {
    Slider(value = fraction.coerceIn(0f, 1f), onValueChange = { drag = it }, enabled = length > 0,
        onValueChangeFinished = {
          drag?.let { YouTubeMusicEngine.seekTo((it * length).toLong()) }
          drag = null
        },
        colors = musicV3SliderColors(ApprovedHomePalette.Accent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
          contentDescription = "Position, ${MusicV3Rules.formatPosition(live)} of ${MusicV3Rules.formatPosition(length)}"
        })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      val shown = drag?.let { (it * length).toLong() } ?: live
      Text(MusicV3Rules.formatPosition(shown), color = ApprovedHomePalette.Secondary, fontSize = 23.sp)
      if (length > 0) Text(MusicV3Rules.formatPosition(length), color = ApprovedHomePalette.Secondary, fontSize = 23.sp)
    }
  }
}

@Composable
private fun Transport(model: MusicV3NowPlaying) {
  val enabled = model.transportEnabled
  val tint = ApprovedHomePalette.Text.copy(alpha = if (enabled) 1f else 0.3f)
  Row(Modifier.fillMaxWidth().padding(top = 18.dp).height(120.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(64.dp, Alignment.CenterHorizontally)) {
    MusicV3RoundButton("Previous", size = 96.dp, enabled = enabled, onClick = YouTubeMusicEngine::previous) {
      MusicV3GlyphIcon(MusicV3Glyph.PREVIOUS, tint, Modifier.size(44.dp))
    }
    val label = if (model.playing) "Pause" else "Play"
    MusicV3RoundButton(label, size = 120.dp, enabled = enabled,
        fill = ApprovedHomePalette.Text.copy(alpha = if (enabled) 1f else 0.3f),
        onClick = { if (model.playing) YouTubeMusicEngine.pause() else YouTubeMusicEngine.play() }) {
      MusicV3GlyphIcon(if (model.playing) MusicV3Glyph.PAUSE else MusicV3Glyph.PLAY, ApprovedHomePalette.Card,
          Modifier.size(52.dp))
    }
    MusicV3RoundButton("Next", size = 96.dp, enabled = enabled, onClick = YouTubeMusicEngine::next) {
      MusicV3GlyphIcon(MusicV3Glyph.NEXT, tint, Modifier.size(44.dp))
    }
  }
}

/** The WebView refused to start on its own: one big, obvious Play. */
@Composable
private fun BigPlay() {
  val shape = RoundedCornerShape(60.dp)
  Row(Modifier.fillMaxWidth().height(120.dp).clip(shape).background(ApprovedHomePalette.Text)
      .tvFocusable(shape, ringColor = ApprovedHomePalette.Accent, onClick = YouTubeMusicEngine::play)
      .semantics { contentDescription = "Play" }, verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)) {
    MusicV3GlyphIcon(MusicV3Glyph.PLAY, ApprovedHomePalette.Card, Modifier.size(52.dp))
    Text("Play", color = ApprovedHomePalette.Card, fontSize = 34.sp, fontWeight = FontWeight.Medium)
  }
}

/** Portal speaker volume (STREAM_MUSIC). Works during ads too. */
@Composable
internal fun MusicV3VolumeRow(room: String = "") {
  val context = androidx.compose.ui.platform.LocalContext.current
  val volume = remember(context) { MusicVolume(context) }
  var level by remember(volume) { mutableStateOf(volume.read()) }
  androidx.compose.runtime.DisposableEffect(volume) {
    val handle = volume.observe { level = it }
    level = volume.read()
    onDispose { handle.close() }
  }
  val max = level.maxStep
  Row(Modifier.fillMaxWidth().padding(top = 18.dp).heightIn(min = MusicV3Dims.Touch),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    MusicV3GlyphIcon(MusicV3Glyph.SPEAKER, ApprovedHomePalette.Secondary, Modifier.size(32.dp))
    Slider(value = level.step.toFloat(), valueRange = 0f..max.coerceAtLeast(1).toFloat(),
        steps = (max - 1).coerceAtLeast(0), enabled = max > 0,
        onValueChange = { v -> val step = Math.round(v); if (step != level.step) level = volume.setStep(step) },
        colors = musicV3SliderColors(ApprovedHomePalette.Text),
        modifier = Modifier.weight(1f).heightIn(min = MusicV3Dims.Touch)
            .semantics { contentDescription = if (room.isBlank()) "Volume" else "$room volume" })
  }
}

@Composable
private fun musicV3SliderColors(active: Color) = SliderDefaults.colors(
    thumbColor = ApprovedHomePalette.Text,
    activeTrackColor = active,
    inactiveTrackColor = ApprovedHomePalette.Border,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)
