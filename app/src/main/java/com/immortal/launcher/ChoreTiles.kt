/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/** Sizes and colours for the chores surfaces (kids get big targets: 96 dp picture tiles). */
internal object ChoreTokens {
  val Tile = 96.dp
  val TilePicture = 68.dp
  val TileRadius = 24.dp
  val Badge = 34.dp
  val AvatarSmall = 64.dp
  val AvatarLarge = 96.dp
  val BarHeight = 14.dp
  val Gap = 12.dp
  val Star = Color(0xFFF2B630)
  val Done = Color(0xFF34A877)
  val Waiting = Color(0xFFE0A33A)
  private val LightFills = listOf(0xFFFFD9C7, 0xFFFFE9A8, 0xFFCDEFD9, 0xFFC9E3FB, 0xFFE4D6FA, 0xFFFAD2E4, 0xFFD3EFEE, 0xFFE6E0D6)
  private val DarkFills = listOf(0xFF7A3E25, 0xFF6E5A12, 0xFF2D5E40, 0xFF244E77, 0xFF4E3A79, 0xFF74304F, 0xFF22605D, 0xFF5A5246)

  fun kidFill(index: Int, dark: Boolean): Color =
      Color((if (dark) DarkFills else LightFills)[Math.floorMod(index, ChoreLimits.COLORS)])

  /** Tile fill: soft green when done, soft amber while it waits for a parent. */
  fun tileFill(item: ChoreBoard.Item, chrome: Color): Color = when {
    item.waiting -> Waiting.copy(alpha = 0.22f)
    item.done -> Done.copy(alpha = 0.22f)
    else -> chrome
  }
}

/** A kid's round avatar: their emoji on their colour. */
@Composable
internal fun ChoreKidAvatar(avatar: String, color: Int, size: Dp) {
  val dark = LocalApprovedPalette.current.dark
  Box(Modifier.size(size).clip(CircleShape).background(ChoreTokens.kidFill(color, dark)), contentAlignment = Alignment.Center) {
    Text(avatar, fontSize = (size.value * 0.52f).sp)
  }
}

/** "12 / 20 ★" progress toward the weekly goal. */
@Composable
internal fun ChoreGoalBar(week: ChoreBoard.Week, modifier: Modifier = Modifier) {
  if (week.goal <= 0) return
  val shape = RoundedCornerShape(ChoreTokens.BarHeight / 2)
  Box(modifier.fillMaxWidth().height(ChoreTokens.BarHeight).clip(shape).background(ApprovedHomePalette.Chrome)) {
    Box(Modifier.fillMaxWidth(ChoreBoard.progress(week)).height(ChoreTokens.BarHeight).clip(shape)
        .background(if (week.reached) ChoreTokens.Done else ChoreTokens.Star))
  }
}

/** One big picture tile; tapping ticks it (or takes back a mis-tap). */
@Composable
internal fun ChoreTile(item: ChoreBoard.Item, onTap: () -> Unit) {
  val shape = RoundedCornerShape(ChoreTokens.TileRadius)
  val state = when { item.waiting -> "waiting for a grown-up"; item.done -> "done"; else -> "to do" }
  Column(Modifier.width(ChoreTokens.Tile), horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(ChoreTokens.Tile).clip(shape).background(ChoreTokens.tileFill(item, ApprovedHomePalette.Chrome))
        .border(if (item.done) 3.dp else ApprovedOutlineWidth, if (item.done) ChoreTokens.Done else ApprovedHomePalette.Outline, shape)
        .semantics { contentDescription = "${item.chore.title}, ${stars(item.chore.stars)}, $state" }
        .tvFocusable(shape, onClick = onTap), contentAlignment = Alignment.Center) {
      Image(painterResource(RoutinePictureRes.pic(item.chore.pic)), null, Modifier.size(ChoreTokens.TilePicture))
      if (item.done) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(ChoreTokens.Badge).clip(CircleShape)
          .background(if (item.waiting) ChoreTokens.Waiting else ChoreTokens.Done), contentAlignment = Alignment.Center) {
        Text(if (item.waiting) "…" else "✓", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
      }
    }
    Text("★".repeat(item.chore.stars), color = ChoreTokens.Star, fontSize = 16.sp, lineHeight = 20.sp, maxLines = 1,
        modifier = Modifier.padding(top = 4.dp))
    Text(item.chore.title, color = ApprovedHomePalette.Secondary, fontSize = 16.sp, lineHeight = 20.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
  }
}

private fun stars(n: Int): String = if (n == 1) "1 star" else "$n stars"
