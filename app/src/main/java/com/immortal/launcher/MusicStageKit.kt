/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Capsule styles: the mock's `.cap` / `.ub` (quiet), `.cap.pri` / `.ub.solid` (ink), `.cap.ember`. */
internal enum class MusicPillStyle { QUIET, SOLID, EMBER }

private const val PILL_DISABLED_ALPHA = 0.32f
private val PillShape = RoundedCornerShape(999.dp)

/** A capsule button at least the stage's touch target tall; [armed] tints it ember (sleep timer on). */
@Composable
internal fun MusicStagePill(
    label: String,
    modifier: Modifier = Modifier,
    style: MusicPillStyle = MusicPillStyle.QUIET,
    glyph: MusicV3Glyph? = null,
    icon: String? = null,
    armed: Boolean = false,
    enabled: Boolean = true,
    spoken: String = label,
    onClick: () -> Unit,
) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  val text = when (style) {
    MusicPillStyle.QUIET -> if (armed) ink.ember else ink.muted
    MusicPillStyle.SOLID -> ink.inverse
    MusicPillStyle.EMBER -> EmberInk
  }
  val fill = when (style) {
    MusicPillStyle.QUIET -> Modifier.background(ink.fill, PillShape).border(1.dp, ink.line, PillShape)
    MusicPillStyle.SOLID -> Modifier.background(ink.ink, PillShape)
    MusicPillStyle.EMBER -> Modifier.background(Brush.verticalGradient(listOf(HearthpaneMark.EmberLight, HearthpaneMark.Ember)), PillShape)
  }
  Row(modifier.heightIn(min = dims.target.dp).alpha(if (enabled) 1f else PILL_DISABLED_ALPHA).clip(PillShape).then(fill)
      .tvFocusable(PillShape, ringColor = ink.ember, enabled = enabled, onClick = onClick)
      .semantics { role = Role.Button; contentDescription = spoken }
      .padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    val size = if (dims == MusicStageDims.COMPACT) 26.dp else 32.dp
    glyph?.let { MusicV3GlyphIcon(it, text, Modifier.size(size)) }
    icon?.let { ApprovedIcon(it, Modifier.size(size), text) }
    Text(label, color = text, fontSize = (dims.body * 0.9f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

/** Dark text on the ember gradient (the mock's `#2a1406`). */
private val EmberInk = Color(0xFF2A1406)

/** An opaque rounded card (popovers, menus): never translucent, so nothing reads through it. */
@Composable
internal fun MusicStageCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  val ink = LocalMusicStageInk.current
  val shape = RoundedCornerShape(30.dp)
  val none = remember { MutableInteractionSource() }
  Column(modifier.clip(shape).background(ink.card, shape).border(1.dp, ink.line, shape)
      // Taps on the card's padding don't fall through to the dismiss layer.
      .clickable(none, indication = null) {}.padding(horizontal = 30.dp, vertical = 22.dp), content = content)
}

/** "Section title  small detail …  [actions]" (the mock's `.sh`). */
@Composable
internal fun MusicStageHeading(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  Row(modifier.fillMaxWidth().heightIn(min = dims.target.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(title, color = ink.ink, fontSize = dims.heading.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    Text(detail.orEmpty(), color = ink.muted, fontSize = dims.small.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f))
    actions?.let { Row(horizontalArrangement = Arrangement.spacedBy(12.dp), content = it) }
  }
}

/** Full-area, untinted tap catcher that closes a popover; it sits below the player in z-order. */
@Composable
internal fun BoxScope.MusicStageDismissLayer(onDismiss: () -> Unit) {
  val none = remember { MutableInteractionSource() }
  Box(Modifier.matchParentSize().clickable(none, indication = null, onClick = onDismiss))
}

/** The stage toast: an opaque pill, one or two lines, with an optional action (Undo). */
@Composable
internal fun MusicStageToast(text: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  val shape = RoundedCornerShape(999.dp)
  Row(modifier.clip(shape).background(ink.card, shape).border(1.dp, ink.line, shape)
      .padding(start = 34.dp, end = if (action == null) 34.dp else 10.dp, top = 8.dp, bottom = 8.dp)
      .heightIn(min = (dims.target - 16).dp)
      .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    Text(text, color = ink.ink, fontSize = dims.small.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    action?.let { (label, run) -> MusicStagePill(label, style = MusicPillStyle.SOLID, onClick = run) }
  }
}

/** A quiet one-line note with a glyph (quiet hours, halt, sleep timer). */
@Composable
internal fun MusicStageNote(text: String, glyph: MusicV3Glyph?, icon: String? = null, ember: Boolean = false,
    modifier: Modifier = Modifier) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  val tint = if (ember) ink.ember else ink.muted
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    val size = if (dims == MusicStageDims.COMPACT) 22.dp else 28.dp
    glyph?.let { MusicV3GlyphIcon(it, tint, Modifier.size(size)) }
    icon?.let { ApprovedIcon(it, Modifier.size(size), tint) }
    Text(text, color = tint, fontSize = dims.small.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
  }
}

/** A centred message for an empty sheet tab (keeps [MusicV3Empty]'s look in stage colours). */
@Composable
internal fun MusicStageEmpty(text: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  Column(modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    Text(text, color = ink.muted, fontSize = dims.body.sp, lineHeight = (dims.body * 1.4f).sp)
    action?.let { (label, run) -> MusicStagePill(label, style = MusicPillStyle.SOLID, onClick = run) }
  }
}
