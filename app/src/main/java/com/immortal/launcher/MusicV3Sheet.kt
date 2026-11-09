/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Space under the last row of a sheet tab, past the part of the sheet below the screen. */
private const val BODY_TAIL = 56f

/** The sheet's callbacks and what its bottom bar shows. */
internal data class MusicV3SheetInputs(
    val idle: Boolean,
    val next: YtTrack?,
    /** Which header part takes touches right now (they swap at different points of the spring). */
    val peekActive: Boolean,
    val tabsActive: Boolean,
    val onOpen: () -> Unit,
    val onClose: () -> Unit,
    val onTab: (MusicSheetTab) -> Unit,
    val onDragStart: () -> Unit,
    val onSettled: (Boolean, Float) -> Unit,
)

/**
 * The pull-up sheet: a "Browse · Up next" bar while closed, the tabs while open, and [body] (the
 * current tab). Its top edge comes from [motion] in the layout phase, on the same spring as the
 * player, so the two can never meet. Only its header (and the strip) accept drags.
 */
@Composable
internal fun MusicV3Sheet(
    layout: MusicStageLayout,
    motion: MusicStageMotion,
    state: MusicSheetState,
    inputs: MusicV3SheetInputs,
    body: @Composable () -> Unit,
) {
  val ink = LocalMusicStageInk.current
  val spec = layout.spec
  val radius = if (spec.compact) 30.dp else 44.dp
  val shape = RoundedCornerShape(topStart = radius, topEnd = radius)
  Column(Modifier.stageOffset { 0f to motion.frame(layout).sheetTop }.fillMaxWidth().height(layout.sheetHeight.dp)
      .shadow(30.dp, shape, clip = false).clip(shape).background(ink.sheet).border(1.dp, ink.line, shape)
      .semantics { paneTitle = "Browse and Up next" }) {
    Box(Modifier.fillMaxWidth().height(spec.peek.dp)
        .musicSheetDrag(motion, layout.sheetTravel, inputs.onDragStart, inputs.onSettled)) {
      Box(Modifier.align(Alignment.TopCenter).padding(top = if (spec.compact) 10.dp else 14.dp)
          .size(if (spec.compact) 64.dp else 84.dp, if (spec.compact) 6.dp else 8.dp).clip(CircleShape).background(ink.faint))
      val inner = Modifier.fillMaxSize().padding(start = spec.pad.dp, end = spec.pad.dp,
          top = if (spec.compact) 20.dp else 30.dp, bottom = if (spec.compact) 6.dp else 12.dp)
      if (inputs.peekActive) PeekBar(inputs, inner.graphicsLayer { alpha = motion.frame(layout).peekAlpha })
      if (inputs.tabsActive) Tabs(state.tab, inputs, inner.graphicsLayer { alpha = motion.frame(layout).tabsAlpha })
    }
    val tail = layout.sheetOpenTop - layout.sheetSearchTop + MusicStageGeometry.OVERSHOOT + BODY_TAIL
    Box(Modifier.weight(1f).fillMaxWidth().padding(start = spec.pad.dp, end = spec.pad.dp, bottom = tail.dp),
        contentAlignment = Alignment.TopCenter) {
      Box(Modifier.widthIn(max = MAX_BODY_WIDTH.dp).fillMaxSize()) { body() }
    }
  }
}

/** The mock's `.wrap`: lists stay readable on the wide Portal. */
private const val MAX_BODY_WIDTH = 1380

@Composable
private fun PeekBar(inputs: MusicV3SheetInputs, modifier: Modifier) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  val shape = RoundedCornerShape(28.dp)
  Row(modifier.clip(shape).tvFocusableRow(focusFill = ink.fill2, onClick = inputs.onOpen)
      .semantics {
        role = Role.Button
        contentDescription = if (inputs.idle) "Browse your library" else "Browse and see what plays next"
      }.padding(start = 16.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    MusicV3GlyphIcon(if (inputs.idle) MusicV3Glyph.GRID else MusicV3Glyph.LIST, ink.muted, Modifier.size(38.dp))
    Text(buildAnnotatedString {
      withStyle(SpanStyle(color = ink.ink, fontWeight = FontWeight.Bold)) { append(MusicV3StageCopy.BROWSE) }
      withStyle(SpanStyle(color = ink.muted, fontWeight = FontWeight.SemiBold)) {
        append(" · " + MusicV3StageCopy.peekLabel(inputs.idle))
      }
    }, fontSize = (dims.body * 1.05f).sp, maxLines = 1)
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.End)) {
      val next = inputs.next
      when {
        inputs.idle -> Text(MusicV3StageCopy.PEEK_IDLE_DETAIL, color = ink.muted, fontSize = dims.small.sp, maxLines = 1)
        next == null -> Text(MusicV3StageCopy.NOTHING_QUEUED, color = ink.muted, fontSize = dims.small.sp, maxLines = 1)
        else -> {
          val thumb = (dims.thumb * 0.72f).dp
          MusicV3Thumb(next.thumbnailUrl, Modifier.size(thumb), RoundedCornerShape(10.dp), displaySize = thumb)
          Text(buildAnnotatedString {
            append("Next · ")
            withStyle(SpanStyle(color = ink.ink, fontWeight = FontWeight.SemiBold)) { append(next.title) }
            if (next.artist.isNotBlank()) append(" · ${next.artist}")
          }, color = ink.muted, fontSize = dims.small.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
    MusicV3GlyphIcon(MusicV3Glyph.UP, ink.muted, Modifier.size(44.dp))
  }
}

@Composable
private fun Tabs(selected: MusicSheetTab, inputs: MusicV3SheetInputs, modifier: Modifier) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  val compact = dims == MusicStageDims.COMPACT
  Box(modifier, contentAlignment = Alignment.Center) {
    val group = RoundedCornerShape(999.dp)
    Row((if (compact) Modifier else Modifier.clip(group).background(ink.fill2).border(1.dp, ink.line, group).padding(6.dp)),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 4.dp)) {
      MusicSheetTab.entries.forEach { tab -> TabButton(tab, tab == selected, compact) { inputs.onTab(tab) } }
    }
    MusicV3RoundButton(MusicV3StageCopy.CLOSE_SHEET, Modifier.align(Alignment.CenterEnd), size = dims.target.dp,
        fill = ink.fill2, onClick = inputs.onClose) {
      MusicV3GlyphIcon(MusicV3Glyph.DOWN, ink.muted, Modifier.size(if (compact) 30.dp else 40.dp))
    }
  }
}

@Composable
private fun TabButton(tab: MusicSheetTab, on: Boolean, compact: Boolean, onClick: () -> Unit) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  val shape = RoundedCornerShape(999.dp)
  val fill = when {
    on && compact -> ink.ink
    on -> ink.selected
    compact -> ink.fill2
    else -> Color.Transparent
  }
  val text = when {
    on && compact -> ink.inverse
    on -> ink.ink
    else -> ink.muted
  }
  Box(Modifier.heightIn(min = MusicStageGeometry.MIN_TARGET.dp).height((dims.tab - if (compact) 0f else 12f).coerceAtLeast(64f).dp)
      .clip(shape).background(fill).tvFocusable(shape, focusScale = 1f, ringColor = ink.ember, onClick = onClick)
      .semantics { role = Role.Tab; selected = on }.padding(horizontal = if (compact) 22.dp else 34.dp),
      contentAlignment = Alignment.Center) {
    Text(tab.label, color = text, fontSize = dims.tabText.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}
