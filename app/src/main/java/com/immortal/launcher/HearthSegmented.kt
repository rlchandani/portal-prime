/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.ApprovedPalette
import com.immortal.launcher.ui.theme.HearthpaneTouchTargets
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlin.math.roundToInt

/**
 * The one shared Hearthpane segmented control: a rounded-rect track with a single raised pill that
 * **glides** to the selected option. It replaces four bespoke controls (Control Center / Alerts
 * `CcSeg`, Calls tabs, Tools category chips, Settings one-choice tiles); each of those is now a thin
 * adapter that supplies this control with its own palette, a11y and click wiring.
 *
 * The signature premium detail is the glide: the selected pill is ONE element whose x-offset and
 * width animate (a single [animateFloatAsState] each, GPU translation only — no per-frame raster),
 * so it stays 60 fps on the Portal+ and the Mini. When the OS "reduce motion" is on (or a caller
 * passes [reduceMotion]) the pill snaps instead.
 *
 * Width modes: pass [weights] for a stretched track (Calls' compact header), [fillWidth] for an
 * equal-width track (Settings tiles, Tools), or neither for content-sized options (Control Center,
 * where options also scroll). Each segment's a11y + click chain comes from [cellModifier] so every
 * adapter keeps its exact announced role/state.
 */
@Composable
internal fun HearthSegmented(
    segments: List<HearthSegment>,
    selectedIndex: Int,
    colors: HearthSegmentedColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
    weights: List<Float>? = null,
    labelSize: TextUnit = HearthSegmentedTokens.LabelSize,
    reduceMotion: Boolean = rememberReducedMotion(),
    selectedSegmentModifier: Modifier = Modifier,
    cellModifier: @Composable (index: Int, selected: Boolean, label: String) -> Modifier,
) {
  val density = LocalDensity.current
  val gapPx = with(density) { HearthSegmentedTokens.SegmentGap.toPx() }.roundToInt()
  // Each cell reports its measured width; the pill geometry is derived from these (pure helper).
  val widths = remember(segments.size) { mutableStateListOf(*Array(segments.size) { 0 }) }
  val hasSelection = selectedIndex in segments.indices
  val measured = hasSelection && widths.size == segments.size && widths.all { it > 0 }
  val index = selectedIndex.coerceIn(0, (segments.size - 1).coerceAtLeast(0))

  val pillShape = RoundedCornerShape(HearthSegmentedTokens.PillCorner)
  val trackShape = RoundedCornerShape(HearthSegmentedTokens.TrackCorner)
  val spec = if (reduceMotion) snap() else tween<Float>(HearthSegmentedTokens.GlideDurationMs, easing = HearthSegmentedTokens.GlideEasing)

  val targetOffset = if (measured) HearthSegmentedGeometry.pillOffsetPx(widths, gapPx, index).toFloat() else 0f
  val targetWidth = if (measured) HearthSegmentedGeometry.pillWidthPx(widths, index).toFloat() else 0f
  val animOffset by animateFloatAsState(targetOffset, spec, label = "segPillOffset")
  val animWidth by animateFloatAsState(targetWidth, spec, label = "segPillWidth")

  Box(
      modifier
          .clip(trackShape)
          .background(colors.track)
          .then(if (enabled) Modifier else Modifier.alpha(HearthSegmentedTokens.DisabledAlpha))
          .padding(HearthSegmentedTokens.TrackPadding)) {
    // The sliding pill, drawn behind the labels. The outer box fills the padded track area so the
    // inner pill's fillMaxHeight resolves to the row height; only the inner pill moves and resizes.
    if (measured) {
      Box(Modifier.matchParentSize()) {
        Box(
            Modifier
                .offset { IntOffset(animOffset.roundToInt(), 0) }
                .width(with(density) { animWidth.toDp() })
                .fillMaxHeight()
                .then(if (colors.elevated) Modifier.shadow(HearthSegmentedTokens.PillElevation, pillShape) else Modifier)
                .background(colors.pill, pillShape)
                .then(if (colors.pillEdge.alpha > 0f) Modifier.border(1.dp, colors.pillEdge, pillShape) else Modifier))
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HearthSegmentedTokens.SegmentGap)) {
      segments.forEachIndexed { i, segment ->
        val selected = i == selectedIndex
        val labelColor by animateColorAsState(
            if (selected) colors.selectedLabel else colors.unselectedLabel,
            if (reduceMotion) snap() else tween(HearthSegmentedTokens.GlideDurationMs, easing = HearthSegmentedTokens.GlideEasing),
            label = "segLabel")
        val widthMod = when {
          weights != null -> Modifier.weight(weights.getOrElse(i) { 1f })
          fillWidth -> Modifier.weight(1f)
          else -> Modifier
        }
        Box(
            widthMod
                .heightIn(min = HearthSegmentedTokens.MinSegmentHeight)
                .onSizeChanged { if (i < widths.size) widths[i] = it.width }
                .clip(pillShape)
                .then(cellModifier(i, selected, segment.label))
                .then(if (selected) selectedSegmentModifier else Modifier)
                .padding(horizontal = HearthSegmentedTokens.SegmentHPadding),
            contentAlignment = Alignment.Center) {
          Row(verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(HearthSegmentedTokens.IconGap)) {
            segment.icon?.invoke(labelColor)
            Text(segment.label, color = labelColor, fontSize = labelSize, maxLines = 1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
          }
        }
      }
    }
  }
}

/** One option: its label and an optional leading icon slot, tinted by the control to match the label. */
internal data class HearthSegment(val label: String, val icon: (@Composable (tint: Color) -> Unit)? = null)

/** Which emphasis a segmented control draws with. */
internal enum class HearthSegmentedVariant { NEUTRAL, ACCENT }

/**
 * The resolved colours a [HearthSegmented] draws, kept immutable so a tap never mutates shared
 * state. Build them from a palette with [HearthSegmentedDefaults]; Control Center supplies its own.
 */
internal data class HearthSegmentedColors(
    val track: Color,
    val pill: Color,
    val pillEdge: Color,
    val selectedLabel: Color,
    val unselectedLabel: Color,
    val elevated: Boolean,
)

/** Factory for the two approved variants over the app palette. */
internal object HearthSegmentedDefaults {
  /** Grey/white raised pill (CC/Alerts, Calls, Tools). Light gets a soft shadow; dark a hairline. */
  fun colors(palette: ApprovedPalette, variant: HearthSegmentedVariant = HearthSegmentedVariant.NEUTRAL): HearthSegmentedColors =
      when (variant) {
        HearthSegmentedVariant.NEUTRAL -> HearthSegmentedColors(
            track = palette.surface2, pill = palette.segOn,
            pillEdge = if (palette.dark) palette.cardEdge else Color.Transparent,
            selectedLabel = palette.text, unselectedLabel = palette.secondary, elevated = !palette.dark)
        HearthSegmentedVariant.ACCENT -> HearthSegmentedColors(
            track = palette.surface2, pill = palette.accent, pillEdge = Color.Transparent,
            selectedLabel = palette.accentInk, unselectedLabel = palette.secondary, elevated = false)
      }
}

/** Sizing, shape and motion tokens for the control — the single source so no call site hardcodes them. */
internal object HearthSegmentedTokens {
  val TrackCorner = 16.dp
  val PillCorner = 12.dp
  val TrackPadding = 4.dp
  val SegmentGap = 2.dp
  val IconGap = 8.dp
  /** Hearthpane's 64 dp touch floor, so every option is reachable from across the room. */
  val MinSegmentHeight = HearthpaneTouchTargets.MINIMUM_DP.dp
  val SegmentHPadding = 20.dp
  val LabelSize = 22.sp
  val IconSize = 26.dp
  val PillElevation = 2.dp
  const val DisabledAlpha = 0.45f
  /** ~300 ms with an iOS-toggle ease (cubic-bezier .32,.72,0,1): a smooth glide, settled well under a tap cadence. */
  const val GlideDurationMs = 300
  val GlideEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
}

/**
 * Pure geometry for the sliding pill — kept out of the composable so it is unit-tested. Given the
 * measured width of every option (left→right) and the inter-option gap, it returns where the pill
 * sits and how wide it is for a selected index.
 */
internal object HearthSegmentedGeometry {
  /** Left edge of the pill: the widths of every earlier option plus one gap per earlier option. */
  fun pillOffsetPx(widths: List<Int>, gapPx: Int, index: Int): Int {
    if (widths.isEmpty()) return 0
    val i = index.coerceIn(0, widths.size - 1)
    var offset = 0
    for (k in 0 until i) offset += widths[k] + gapPx
    return offset
  }

  /** Width of the pill: the selected option's own measured width. */
  fun pillWidthPx(widths: List<Int>, index: Int): Int {
    if (widths.isEmpty()) return 0
    return widths[index.coerceIn(0, widths.size - 1)]
  }
}
