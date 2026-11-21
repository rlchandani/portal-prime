/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import kotlin.math.roundToInt

/*
 * Rail / bottom-bar tabs whose selection is read at draw (and semantics) time, not in composition.
 *
 * Switching screens used to recompose the whole rail: each item got a new `on` flag, so its
 * modifier chain gained or lost the `ccCard` nodes (a structural change: new render node, clip,
 * fill, border) and its icon and label were recomposed with new colours. Here every tab keeps one
 * fixed tree; the selected card is always composed in its own layer and only its alpha changes,
 * the icon tint and label colour are read while drawing, and the "selected" semantics are updated
 * by a node that observes the selection. A switch re-runs a layer property, two draws and one
 * semantics update per changed tab, and composes nothing.
 */

/** Never selected (Leave kids). */
internal val CcNeverSelected: State<Boolean> = object : State<Boolean> { override val value = false }

/** The selected tab's `.card` behind the tab, always composed; [selected] only toggles its layer's alpha. */
@Composable
internal fun BoxScope.CcSelectionCard(shape: Shape, selected: State<Boolean>) {
  Box(Modifier.matchParentSize().graphicsLayer { alpha = if (selected.value) 1f else 0f }.ccCard(shape))
}

/** [CcIcon] whose tint is read while drawing, so a tint change redraws it without recomposing. */
@Composable
internal fun CcIcon(name: String, tint: ColorProducer, size: Dp, modifier: Modifier = Modifier) {
  val painter = rememberVectorPainter(CcIcons[name])
  val filters = remember { CcTintFilters() }
  Box(modifier.size(size).drawBehind {
    val place = ccFitCentered(painter.intrinsicSize, this.size)
    translate(place.left, place.top) {
      with(painter) { draw(Size(place.width, place.height), colorFilter = filters.of(tint())) }
    }
  })
}

/** A one-line label (Material `Text` metrics) whose colour is read while drawing. */
@Composable
internal fun CcTabLabel(text: String, color: ColorProducer, fontSize: TextUnit, overflow: TextOverflow = TextOverflow.Clip) {
  val style = LocalTextStyle.current.merge(TextStyle(fontSize = fontSize))
  BasicText(text, style = style, overflow = overflow, maxLines = 1, color = color)
}

/** Reuses one tint filter per colour (a tab only ever has two). */
private class CcTintFilters {
  private var color = Color.Unspecified
  private var filter: ColorFilter? = null

  fun of(c: Color): ColorFilter = filter?.takeIf { c == color } ?: ColorFilter.tint(c).also { color = c; filter = it }
}

/** Where a painter of [intrinsic] size draws inside [dst]: `ContentScale.Fit`, centred (as `Modifier.paint` / `Icon`). */
internal data class CcPlacement(val left: Float, val top: Float, val width: Float, val height: Float)

internal fun ccFitCentered(intrinsic: Size, dst: Size): CcPlacement {
  if (dst.width <= 0f || dst.height <= 0f) return CcPlacement(0f, 0f, 0f, 0f)
  val src = Size(if (intrinsic.width.isFinite()) intrinsic.width else dst.width, if (intrinsic.height.isFinite()) intrinsic.height else dst.height)
  if (src.width <= 0f || src.height <= 0f) return CcPlacement(0f, 0f, 0f, 0f)
  val scale = ContentScale.Fit.computeScaleFactor(src, dst)
  val w = src.width * scale.scaleX
  val h = src.height * scale.scaleY
  val offset = Alignment.Center.align(IntSize(w.roundToInt(), h.roundToInt()), IntSize(dst.width.roundToInt(), dst.height.roundToInt()),
      androidx.compose.ui.unit.LayoutDirection.Ltr)
  return CcPlacement(offset.x.toFloat(), offset.y.toFloat(), w, h)
}

/** [ccSpeak] for a tab: one node saying [description], its "selected" state observed from [selected]. */
internal fun Modifier.ccSpeakTab(description: String, selected: State<Boolean>): Modifier = this then CcSpeakTabElement(description, selected)

private data class CcSpeakTabElement(val description: String, val selected: State<Boolean>) : ModifierNodeElement<CcSpeakTabNode>() {
  override fun create() = CcSpeakTabNode(description, selected)

  override fun update(node: CcSpeakTabNode) = node.update(description, selected)

  override fun InspectorInfo.inspectableProperties() {
    name = "ccSpeakTab"; properties["description"] = description; properties["selected"] = selected.value
  }
}

private class CcSpeakTabNode(private var description: String, private var source: State<Boolean>) :
    Modifier.Node(), SemanticsModifierNode, ObserverModifierNode {
  private var isSelected = false

  override val shouldClearDescendantSemantics: Boolean get() = true

  override fun onAttach() { observe() }

  override fun onObservedReadsChanged() {
    val before = isSelected
    observe()
    if (isSelected != before) invalidateSemantics()
  }

  fun update(description: String, source: State<Boolean>) {
    val changed = description != this.description || source !== this.source
    this.description = description; this.source = source
    if (!changed) return
    if (isAttached) observe()
    invalidateSemantics()
  }

  private fun observe() { observeReads { isSelected = source.value } }

  override fun SemanticsPropertyReceiver.applySemantics() {
    val node = this@CcSpeakTabNode
    contentDescription = node.description
    selected = node.isSelected
  }
}
