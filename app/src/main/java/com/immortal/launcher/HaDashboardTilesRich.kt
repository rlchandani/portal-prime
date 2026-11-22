/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.immortal.launcher.HaEntityLook.Tone

/** Thermostat (2×2): mode chip, 270° dial with the inside temperature, −/+ target stepper. */
@Composable
internal fun HaClimateTile(d: HaDashboardApi.Detail, stale: Boolean, failed: Boolean, onStep: (Float) -> Unit,
    modifier: Modifier) {
  val dims = LocalHaDims.current
  val c = LocalHaColors.current
  val target = HaClimate.target(d)
  val step = HaClimate.step(d, target)
  Column(modifier.haTile(Tone.PLAIN, stale, failed).padding(dims.tilePad.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      HaWell("flame", Tone.PLAIN)
      HaNameStatus(d.friendlyName, HaClimate.statusLine(d, target), Tone.PLAIN, Modifier.weight(1f).padding(top = 4.dp))
      HaClimate.actionChip(d)?.let { HaModeChip(it) }
    }
    Column(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
      BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        HaDial(current = d.currentTemp ?: target, target = target, size = min(min(maxWidth, maxHeight), dims.dial.dp))
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        HaRoundButton("minus", "Lower target", { onStep(HaClimate.stepped(target, -step, step)) })
        HaText(HaClimate.stepperText(target), dims.button, c.text, Modifier.widthIn(min = 120.dp), FontWeight.Bold,
            align = TextAlign.Center)
        HaRoundButton("plus", "Raise target", { onStep(HaClimate.stepped(target, step, step)) })
      }
    }
  }
}

@Composable
private fun HaModeChip(text: String) {
  val c = LocalHaColors.current
  Row(Modifier.height(40.dp).clip(HaShape.Pill).background(c.amberBg).padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    HaIcon("flame", c.amber, 20.dp)
    HaText(text, LocalHaDims.current.small, c.amber, weight = FontWeight.Bold)
  }
}

@Composable
private fun HaDial(current: Float, target: Float, size: androidx.compose.ui.unit.Dp) {
  val c = LocalHaColors.current
  val dims = LocalHaDims.current
  Box(Modifier.size(size).semantics(mergeDescendants = true) {
    contentDescription = "Inside ${HaClimate.degrees(current)}, target ${HaClimate.degrees(target)}"
  }, contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      val stroke = this.size.minDimension * DIAL_STROKE
      val inset = this.size.minDimension * DIAL_INSET
      val arc = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
      val style = Stroke(stroke, cap = StrokeCap.Round)
      drawArc(c.surface2, DIAL_START, DIAL_SWEEP, false, Offset(inset, inset), arc, style = style)
      drawArc(c.amber, DIAL_START, DIAL_SWEEP * HaClimate.fraction(target), false, Offset(inset, inset), arc, style = style)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      HaText(HaClimate.degrees(current), minOf(dims.huge, (size.value * HUGE_SHARE).toInt()), c.text, serif = true,
          lineHeight = 1f)
      HaText("Inside now", dims.small, c.sec, Modifier.padding(top = 4.dp))
    }
  }
}

/** Now playing (2×1): art, title, "artist · speaker", and 72/64 dp transport buttons. */
@Composable
internal fun HaMediaTile(d: HaDashboardApi.Detail, now: NowPlayingState?, stale: Boolean, onMedia: (HaMediaAction) -> Unit,
    modifier: Modifier) {
  val dims = LocalHaDims.current
  val c = LocalHaColors.current
  val title = now?.title?.ifBlank { null } ?: d.mediaTitle ?: "Nothing playing"
  val artist = now?.artist?.ifBlank { null } ?: d.mediaArtist
  val playing = now?.state == PlaybackState.PLAYING
  Row(modifier.haTile(Tone.PLAIN, stale, false).padding(dims.tilePad.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    HaArt(now)
    Column(Modifier.weight(1f)) {
      HaText(title, dims.name, c.text, weight = FontWeight.SemiBold)
      HaText(listOfNotNull(artist, d.friendlyName).joinToString(" · "), dims.status, c.sec, Modifier.padding(top = 2.dp))
      Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        HaRoundButton("prev", "Previous", { onMedia(HaMediaAction.PREVIOUS) }, iconSize = 26.dp)
        HaRoundButton(if (playing) "pause" else "play", if (playing) "Pause" else "Play", { onMedia(HaMediaAction.PLAY_PAUSE) },
            iconSize = 28.dp, fill = c.text, ink = c.bg)
        HaRoundButton("next", "Next", { onMedia(HaMediaAction.NEXT) }, iconSize = 26.dp)
      }
    }
  }
}

internal enum class HaMediaAction { PLAY_PAUSE, NEXT, PREVIOUS }

@Composable
private fun HaArt(now: NowPlayingState?) {
  val shape = RoundedCornerShape(20.dp)
  val m = Modifier.size(LocalHaDims.current.art.dp).clip(shape)
  val art = now?.artBitmap
  if (art != null) {
    Image(art.asImageBitmap(), contentDescription = null, modifier = m, contentScale = ContentScale.Crop)
    return
  }
  // No cover art: the mock's ember/blue placeholder glow.
  Box(m.background(ART_BASE).drawBehind {
    val r = size.minDimension * ART_GLOW_R
    drawRect(Brush.radialGradient(listOf(HearthpaneMark.EmberLight, Color.Transparent),
        center = Offset(size.width * ART_GLOW_A, size.height * ART_GLOW_A), radius = r))
    drawRect(Brush.radialGradient(listOf(ART_BLUE, Color.Transparent),
        center = Offset(size.width * ART_GLOW_B, size.height * ART_GLOW_B), radius = r))
  })
}

private const val DIAL_START = 135f
private const val DIAL_SWEEP = 270f
private const val DIAL_STROKE = 0.07f
private const val DIAL_INSET = 0.06f
private const val HUGE_SHARE = 0.31f
private val ART_BASE = Color(0xFF2A2F3A)
private val ART_BLUE = Color(0xFF176ABB)
private const val ART_GLOW_A = 0.3f
private const val ART_GLOW_B = 0.8f
private const val ART_GLOW_R = 0.7f
