/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val TICK_MS = 1_000L
private const val BAR_TOUCH = 64
private const val BAR_REST = 8
private const val BAR_PRESSED = 16
private const val DISABLED_ALPHA = 0.32f

/**
 * The mock's thin bar (8 dp, 16 dp while pressed) in a 64 dp touch strip, for the scrubber and the
 * volume. Tap or drag; [onCommit] runs on release. [steps] > 0 snaps to that many intervals.
 */
@Composable
internal fun MusicThinBar(
    fraction: Float,
    enabled: Boolean,
    label: String,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
  val ink = LocalMusicStageInk.current
  var pressed by remember { mutableStateOf(false) }
  val preview by rememberUpdatedState(onPreview)
  val commit by rememberUpdatedState(onCommit)
  fun snap(f: Float) = f.coerceIn(0f, 1f).let { if (steps > 0) Math.round(it * steps).toFloat() / steps else it }
  Box(modifier.fillMaxWidth().height(BAR_TOUCH.dp)
      .semantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f, (steps - 1).coerceAtLeast(0))
        if (enabled) setProgress { v -> commit(snap(v)); true } else disabled()
      }
      .pointerInput(enabled, steps) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
          val down = awaitFirstDown()
          pressed = true
          var f = snap(down.position.x / size.width)
          preview(f)
          horizontalDrag(down.id) { change ->
            f = snap(change.position.x / size.width)
            preview(f)
            change.consume()
          }
          pressed = false
          commit(f)
        }
      }
      .alpha(if (enabled) 1f else DISABLED_ALPHA)
      .drawBehind {
        val h = (if (pressed) BAR_PRESSED else BAR_REST).dp.toPx()
        val top = (size.height - h) / 2
        val r = CornerRadius(h / 2)
        drawRoundRect(ink.track, Offset(0f, top), Size(size.width, h), r)
        drawRoundRect(ink.ink.copy(alpha = 0.9f), Offset(0f, top), Size(size.width * fraction.coerceIn(0f, 1f), h), r)
      })
}

/** Position bar with elapsed / remaining times; only this reads the per-second position. */
@Composable
internal fun MusicV3Scrubber(enabled: Boolean, modifier: Modifier = Modifier) {
  val dims = LocalMusicStageDims.current
  val p by rememberProgressSlice()
  val now by produceState(SystemClock.elapsedRealtime(), p.playing, p.atElapsedMs) {
    value = SystemClock.elapsedRealtime()
    while (p.playing) { delay(TICK_MS); value = SystemClock.elapsedRealtime() }
  }
  val length = p.lengthMs
  val live = p.liveMs(now)
  var drag by remember(p.songId) { mutableStateOf<Float?>(null) }
  val fraction = drag ?: if (length > 0) live.toFloat() / length else 0f
  val shown = drag?.let { (it * length).toLong() } ?: live
  Column(modifier) {
    MusicThinBar(fraction, enabled && length > 0,
        "Position, ${MusicV3Rules.formatPosition(live)} of ${MusicV3Rules.formatPosition(length)}",
        onPreview = { drag = it },
        onCommit = { YouTubeMusicEngine.seekTo((it * length).toLong()); drag = null })
    Row(Modifier.fillMaxWidth().offset(y = (-8).dp), horizontalArrangement = Arrangement.SpaceBetween) {
      val ink = LocalMusicStageInk.current
      Text(MusicV3Rules.formatPosition(shown), color = ink.muted, fontSize = dims.small.sp, fontWeight = FontWeight.SemiBold)
      if (length > 0) {
        Text("-" + MusicV3Rules.formatPosition(length - shown), color = ink.muted, fontSize = dims.small.sp,
            fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

/** Shuffle · previous · play/pause · next · repeat, with the mock's sizes. */
@Composable
internal fun MusicV3Transport(model: MusicV3NowPlaying, queue: YtQueue, enabled: Boolean, onToast: (String) -> Unit) {
  val dims = LocalMusicStageDims.current
  val ink = LocalMusicStageInk.current
  val alpha = if (enabled) 1f else DISABLED_ALPHA
  Row(Modifier.fillMaxWidth().alpha(alpha), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween) {
    val shuffled = queue.isShuffled
    SideToggle(if (shuffled) "Shuffle, on" else "Shuffle", MusicV3Glyph.SHUFFLE, shuffled, enabled) {
      YouTubeMusicEngine.setShuffle(!shuffled)
      onToast(if (shuffled) "Shuffle off" else "Shuffle on")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dims.transportGap.dp)) {
      MusicV3RoundButton("Previous", size = dims.skip.dp, enabled = enabled, onClick = YouTubeMusicEngine::previous) {
        MusicV3GlyphIcon(MusicV3Glyph.PREVIOUS, ink.ink, Modifier.size(dims.skipGlyph.dp))
      }
      MusicV3PlayPause(model.playing, dims.play, dims.playGlyph, enabled)
      MusicV3RoundButton("Next", size = dims.skip.dp, enabled = enabled, onClick = YouTubeMusicEngine::next) {
        MusicV3GlyphIcon(MusicV3Glyph.NEXT, ink.ink, Modifier.size(dims.skipGlyph.dp))
      }
    }
    val repeat = queue.repeat
    SideToggle(MusicV3StageCopy.repeatLabel(repeat), MusicV3Glyph.REPEAT, repeat != YtRepeat.OFF, enabled,
        badge = if (repeat == YtRepeat.ONE) "1" else null) {
      val next = MusicV3StageCopy.nextRepeat(repeat)
      YouTubeMusicEngine.setRepeat(next)
      onToast(MusicV3StageCopy.repeatToast(next))
    }
  }
}

@Composable
internal fun MusicV3PlayPause(playing: Boolean, size: Float, glyph: Float, enabled: Boolean) {
  val ink = LocalMusicStageInk.current
  MusicV3RoundButton(if (playing) "Pause" else "Play", size = size.dp, enabled = enabled,
      onClick = { if (playing) YouTubeMusicEngine.pause() else YouTubeMusicEngine.play() }) {
    MusicV3GlyphIcon(if (playing) MusicV3Glyph.PAUSE else MusicV3Glyph.PLAY, ink.ink, Modifier.size(glyph.dp))
  }
}

@Composable
private fun SideToggle(label: String, glyph: MusicV3Glyph, on: Boolean, enabled: Boolean, badge: String? = null,
    onClick: () -> Unit) {
  val dims = LocalMusicStageDims.current
  val ink = LocalMusicStageInk.current
  val tint = if (on) ink.ember else ink.muted
  MusicV3RoundButton(label, size = dims.side.dp, enabled = enabled, onClick = onClick) {
    Box(contentAlignment = Alignment.Center) {
      MusicV3GlyphIcon(glyph, tint, Modifier.size(dims.sideGlyph.dp))
      if (badge != null) {
        Text(badge, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.offset(y = (dims.sideGlyph / 2 + 4).dp))
      } else if (on) {
        Box(Modifier.offset(y = (dims.sideGlyph / 2 + 8).dp).size(7.dp).drawBehind { drawCircle(tint) })
      }
    }
  }
}

/** Portal speaker volume (STREAM_MUSIC), capped during quiet hours. Works during ads too. */
@Composable
internal fun MusicV3StageVolume(room: String) {
  val context = LocalContext.current
  val ink = LocalMusicStageInk.current
  val volume = remember(context) { MusicVolume(context) }
  var level by remember(volume) { mutableStateOf(volume.read()) }
  DisposableEffect(volume) {
    val handle = volume.observe { level = it }
    level = volume.read()
    onDispose { handle.close() }
  }
  val max = level.maxStep
  val glyph = if (LocalMusicStageDims.current == MusicStageDims.COMPACT) 28.dp else 34.dp
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    MusicV3GlyphIcon(MusicV3Glyph.SPEAKER_LOW, ink.muted, Modifier.size(glyph))
    val set: (Float) -> Unit = { f ->
      val step = MusicNightGuard.allowedStep(context, Math.round(f * max), max)
      if (step != level.step) level = volume.setStep(step)
    }
    MusicThinBar(if (max > 0) level.step.toFloat() / max else 0f, max > 0,
        if (room.isBlank()) "Volume" else "$room volume", onPreview = set, onCommit = set,
        modifier = Modifier.weight(1f), steps = max)
    MusicV3GlyphIcon(MusicV3Glyph.SPEAKER, ink.muted, Modifier.size(glyph))
  }
}
