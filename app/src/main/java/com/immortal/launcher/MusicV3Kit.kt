/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/** Shared music v3 sizes (dp on the 1920×1280 dp Portal; text ≥ 22sp, targets ≥ 64dp). */
internal object MusicV3Dims {
  val Touch = 64.dp
  val PaneRadius = 32.dp
  val Gap = 24.dp
}

internal enum class MusicV3ButtonStyle { SOLID, LINE, GHOST, DANGER }

@Composable
internal fun MusicV3Button(
    label: String,
    modifier: Modifier = Modifier,
    style: MusicV3ButtonStyle = MusicV3ButtonStyle.LINE,
    glyph: MusicV3Glyph? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
  val palette = LocalApprovedPalette.current
  val shape = RoundedCornerShape(32.dp)
  val (fill, ink) = when (style) {
    MusicV3ButtonStyle.SOLID -> palette.text to palette.surface
    MusicV3ButtonStyle.DANGER -> palette.error to palette.surface
    MusicV3ButtonStyle.LINE -> palette.surface to palette.text
    MusicV3ButtonStyle.GHOST -> Color.Transparent to palette.text
  }
  val alpha = if (enabled) 1f else 0.4f
  val filled = modifier.heightIn(min = MusicV3Dims.Touch).clip(shape).background(fill.copy(alpha = fill.alpha * alpha))
  val base = if (style == MusicV3ButtonStyle.LINE) filled.approvedOutline(shape) else filled
  Row(base.tvFocusable(shape, ringColor = palette.accent, enabled = enabled, onClick = onClick)
      .padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
    glyph?.let { MusicV3GlyphIcon(it, ink.copy(alpha = alpha), Modifier.size(26.dp)) }
    Text(label, color = ink.copy(alpha = alpha), fontSize = 23.sp, fontWeight = FontWeight.Medium, maxLines = 1)
  }
}

/** Round icon-only control; [label] is its accessibility label. */
@Composable
internal fun MusicV3RoundButton(
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = MusicV3Dims.Touch,
    fill: Color = Color.Transparent,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
  Box(modifier.size(size).clip(CircleShape).background(fill)
      .tvFocusable(CircleShape, ringColor = ApprovedHomePalette.Accent, enabled = enabled, onClick = onClick)
      .semantics { contentDescription = label }, contentAlignment = Alignment.Center) { content() }
}

/** A remote thumbnail (YouTube CDN) with a quiet music-note placeholder. */
@Composable
internal fun MusicV3Thumb(url: String?, modifier: Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
  val context = LocalContext.current
  val bitmap by produceState<Bitmap?>(null, url) {
    if (!url.isNullOrBlank()) MediaArt.deliverUri(context, url) { value = it }
  }
  Box(modifier.clip(shape).background(ApprovedHomePalette.Chrome), contentAlignment = Alignment.Center) {
    val art = bitmap?.takeIf { !it.isRecycled }
    if (art != null) {
      Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize())
    } else {
      ApprovedIcon("music", Modifier.size(28.dp), ApprovedHomePalette.Secondary)
    }
  }
}

/** Account avatar: the channel picture, else the initial on a palette tint. */
@Composable
internal fun MusicV3Avatar(name: String, url: String?, size: Dp) {
  val context = LocalContext.current
  val bitmap by produceState<Bitmap?>(null, url) {
    if (!url.isNullOrBlank()) MediaArt.deliverUri(context, url) { value = it }
  }
  Box(Modifier.size(size).clip(CircleShape).background(ApprovedHomePalette.Selected),
      contentAlignment = Alignment.Center) {
    val art = bitmap?.takeIf { !it.isRecycled }
    if (art != null) {
      Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize())
    } else {
      Text(MusicV3Rules.initial(name), color = ApprovedHomePalette.Accent,
          fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.SemiBold)
    }
  }
}

/** Status toast (never over the player: callers place it in the right pane or on Home). */
@Composable
internal fun MusicV3Toast(text: String, modifier: Modifier = Modifier, icon: String = "info") {
  val palette = LocalApprovedPalette.current
  Row(modifier.widthIn(max = 980.dp).heightIn(min = 84.dp).clip(RoundedCornerShape(42.dp))
      .background(palette.text).padding(start = 26.dp, end = 34.dp, top = 16.dp, bottom = 16.dp)
      .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    ApprovedIcon(icon, Modifier.size(32.dp), palette.surface)
    Text(text, color = palette.surface, fontSize = 24.sp, lineHeight = 32.sp)
  }
}

/** Centered empty/problem state with an optional action. */
@Composable
internal fun MusicV3Empty(
    icon: String,
    message: MusicV3Message,
    modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null,
) {
  Column(modifier.fillMaxSize().padding(bottom = 60.dp), verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally) {
    ApprovedIcon(icon, Modifier.size(64.dp), ApprovedHomePalette.Secondary)
    Text(message.title, color = ApprovedHomePalette.Text, fontSize = 32.sp, lineHeight = 40.sp,
        fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 20.dp).widthIn(max = 760.dp))
    Text(message.detail, color = ApprovedHomePalette.Secondary, fontSize = 23.sp, lineHeight = 32.sp,
        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp).widthIn(max = 640.dp))
    action?.let { (label, onClick) -> MusicV3Button(label, Modifier.padding(top = 28.dp), onClick = onClick) }
  }
}

@Composable
internal fun MusicV3SectionLabel(text: String, modifier: Modifier = Modifier) {
  Text(text, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp,
      modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp))
}
