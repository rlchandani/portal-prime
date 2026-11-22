/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import kotlin.math.roundToInt

/*
 * Rail / bottom-bar tabs whose selection is read at draw (and semantics) time, not in composition.
 *
 * Round 2 (render thread): a tab is drawn as two stacked looks, the resting one (rail colour,
 * secondary tint) cached as its own small texture and the selected one (card, primary tint) shown
 * by layer alpha. Nothing a switch changes is inside a cached texture any more, so a switch
 * re-renders no layer at all: it flips four layer alphas. Before, the whole rail was one cached
 * texture and a switch re-rendered the strip between the two changed tabs (Home to Alerts: four
 * tabs) every time, plus every frame of a rail tap's ripple.
 *
 * Switching screens used to recompose the whole rail: each item got a new `on` flag, so its
 * modifier chain gained or lost the `ccCard` nodes (a structural change: new render node, clip,
 * fill, border) and its icon and label were recomposed with new colours. Here every tab keeps one
 * fixed tree; the selected card is always composed in its own layer and only its alpha changes,
 * the icon tint and label colour are read while drawing, and the "selected" semantics are updated
 * by a node that observes the selection. A switch re-runs a layer property, two draws and one
 * semantics update per changed tab, and composes nothing.
 */

/**
 * A tab's selection: [shown] drives its look (layer alphas, at once on a switch); [spoken] drives its
 * "selected" state for accessibility services, which follows [CcUi.spokenScreen] a few frames later
 * so the switch frame changes no semantics (see [CcScreenSlots]).
 */
@Stable
internal class CcTabSelection(val shown: State<Boolean>, val spoken: State<Boolean>)

private val NEVER: State<Boolean> = object : State<Boolean> { override val value = false }

/** Never selected (Leave kids). */
internal val CcNeverSelected = CcTabSelection(NEVER, NEVER)

/** The selected tab's `.card` behind the tab, always composed; [selected] only toggles its layer's alpha. */
@Composable
internal fun BoxScope.CcSelectionCard(shape: Shape, selected: State<Boolean>) {
  Box(Modifier.matchParentSize().graphicsLayer { alpha = ccLookAlpha(selected.value) }.ccCard(shape))
}

/**
 * One rail / bottom-bar tab, drawn bottom to top: the resting look ([content] in [CcColors.sec]
 * over the rail colour, its own cached texture), the selected card, the selected look ([content]
 * in [CcColors.text]; both looks are only visible through layer alpha), then [overlay] (the badge)
 * and the press ripple in the tappable, speaking box. The looks hold no semantics (labels are
 * drawn, see [CcTabText]), so the tab is still one node saying [description].
 */
@Composable
internal fun CcTab(modifier: Modifier, shape: Shape, selection: CcTabSelection, description: String, enabled: Boolean,
                   onClick: () -> Unit, overlay: @Composable BoxScope.() -> Unit = {}, content: @Composable BoxScope.(Color) -> Unit) {
  val c = LocalCc.current
  val selected = selection.shown
  Box(modifier.ccDisabledLayer(enabled)) {
    Box(Modifier.matchParentSize().ccRegionLayer(c.rail).clip(shape)) { content(c.sec) }
    CcSelectionCard(shape, selected)
    Box(Modifier.matchParentSize().graphicsLayer { alpha = ccLookAlpha(selected.value); this.shape = shape; clip = true }) { content(c.text) }
    Box(Modifier.matchParentSize().clip(shape).clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
        .ccSpeakTab(description, selection.spoken), content = overlay)
  }
}

/** Layer alpha of the selected look (and card): shown only while selected. */
internal fun ccLookAlpha(selected: Boolean): Float = if (selected) 1f else 0f

/**
 * A disabled tab ([CC_DISABLED_ALPHA], as `Modifier.alpha`: a clipped layer) kept as a texture,
 * so its alpha group is rendered once instead of a save-layer every frame. An enabled tab adds nothing.
 */
private fun Modifier.ccDisabledLayer(enabled: Boolean): Modifier =
    if (enabled) this else graphicsLayer(alpha = CC_DISABLED_ALPHA, clip = true, compositingStrategy = CompositingStrategy.Offscreen)

/**
 * A one-line tab label (Material `Text` metrics) laid out and painted exactly as `BasicText`
 * (same style, constraints, clip and paint), but drawn: it adds no semantics node, so a tab can
 * draw its label twice (resting and selected look) and still be one node.
 */
@Composable
internal fun CcTabText(text: String, color: Color, fontSize: TextUnit, overflow: TextOverflow = TextOverflow.Clip) {
  val style = LocalTextStyle.current.merge(TextStyle(fontSize = fontSize))
  val measurer = rememberTextMeasurer(cacheSize = 1)
  val laid = remember { CcLaidText() }
  Layout(Modifier.drawBehind { laid.result?.let { ccPaintText(it, color, overflow) } }) { _, constraints ->
    val result = measurer.measure(text, style, overflow, softWrap = true, maxLines = 1, constraints = constraints)
    laid.result = result
    layout(result.size.width, result.size.height) {}
  }
}

/** The last layout of a [CcTabText] (written by its measure, read by its draw). */
private class CcLaidText { var result: TextLayoutResult? = null }

/** `BasicText`'s paint: clipped only when the text overflows its box, no shadow / decoration, filled. */
internal fun DrawScope.ccPaintText(result: TextLayoutResult, color: Color, overflow: TextOverflow) {
  val canvas = drawContext.canvas
  val clip = result.hasVisualOverflow && overflow != TextOverflow.Visible
  if (clip) { canvas.save(); canvas.clipRect(0f, 0f, result.size.width.toFloat(), result.size.height.toFloat()) }
  try {
    result.multiParagraph.paint(canvas, color, Shadow.None, TextDecoration.None, Fill)
  } finally {
    if (clip) canvas.restore()
  }
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

/** A fixed tint for [CcIcon]'s drawn-tint form (a look of a [CcTab] never changes colour). */
@Composable
internal fun rememberCcTint(color: Color): ColorProducer = remember(color) { ColorProducer { color } }

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
