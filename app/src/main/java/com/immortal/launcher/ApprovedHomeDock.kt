/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/*
 * Home's Home / Apps / Tools bar, with the selection read while drawing (the Control Center `CcTab`
 * pattern). Before, a switch recomposed the whole bar (and, through the selected tab parameter, the
 * whole launcher screen): every tab got a new `active` flag, a new background, a re-resolved icon
 * painter and a re-laid-out label, ~2–8.5 ms of the switch frame. Here each tab is a fixed tree: its
 * fill, icon tint and label colour are read in draw, so a switch redraws two tabs and composes
 * nothing. Its "selected" state follows [rememberHomeSpokenTab], which lands a few frames after the
 * switch together with the pages' accessibility swap ([ApprovedLauncherTabs]).
 */

/** The bar's tabs, in order: id (an [ApprovedLauncherTab.id] and the icon name) and label. */
internal val HomeDockTabs: List<Pair<String, String>> =
    listOf("home" to "Home", "apps" to "Apps", "tools" to "Tools")

/** Frames after a tab switch at which accessibility services learn of it (one quiet frame, as Control Center's). */
internal const val HOME_TAB_EXPOSE_FRAMES = 2

/**
 * The tab accessibility services are told about: [shown] after [HOME_TAB_EXPOSE_FRAMES] frames, so a
 * switch frame changes no semantics. Every switch change that accessibility sees (the dock's
 * "selected", which page is in the tree) follows this one state, so it all lands on one quiet frame.
 * A newer switch restarts the countdown, so a quick double switch exposes only the last tab.
 */
@Composable
internal fun rememberHomeSpokenTab(shown: () -> ApprovedLauncherTab): State<ApprovedLauncherTab> {
  val spoken = remember { mutableStateOf(shown()) }
  val latest = rememberUpdatedState(shown)
  LaunchedEffect(Unit) {
    followAfterFrames(snapshotFlow { latest.value() }, HOME_TAB_EXPOSE_FRAMES) { spoken.value = it }
  }
  return spoken
}

/**
 * Hands each [source] value to [expose] once [frames] frames have passed since it arrived; a newer
 * value cancels the pending one (only the last of a quick double switch is exposed).
 */
internal suspend fun <T> followAfterFrames(source: Flow<T>, frames: Int, expose: (T) -> Unit) {
  source.collectLatest { value ->
    repeat(frames) { withFrameNanos { } }
    expose(value)
  }
}

@Composable
internal fun ApprovedHomeDock(selected: () -> String, spoken: () -> String, onSelect: (String) -> Unit) {
  ApprovedHomeSurface(radius = 26.dp, modifier = Modifier.height(92.dp)) {
    Row(Modifier.fillMaxHeight().padding(8.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
      HomeDockTabs.forEach { (id, label) -> HomeDockTab(id, label, selected, spoken, onSelect) }
    }
  }
}

@Composable
private fun HomeDockTab(id: String, label: String, selected: () -> String, spoken: () -> String,
                        onSelect: (String) -> Unit) {
  val shown = remember(id, selected) { derivedStateOf { selected() == id } }
  val said = remember(id, spoken) { derivedStateOf { spoken() == id } }
  val fill = ApprovedHomePalette.Selected
  val accent = ApprovedHomePalette.Accent
  val secondary = ApprovedHomePalette.Secondary
  val tint = remember(accent, secondary, shown) { ColorProducer { if (shown.value) accent else secondary } }
  val shape = RoundedCornerShape(18.dp)
  val select = rememberUpdatedState(onSelect)
  Row(Modifier.width(176.dp).fillMaxHeight().clip(shape)
      // As `.background(if (active) Selected else Transparent)`: a rectangle under the clip, read while drawing.
      .drawBehind { if (shown.value) drawRect(fill) }
      .homeTabSelected(said)
      .tvFocusable(shape, ringColor = accent, role = Role.Tab) { select.value(id) },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
    ApprovedDrawnTintIcon(id, tint, 30.dp)
    // Material `Text(label, color, 24.sp, lineHeight 32.sp, Medium)`, its colour read while drawing.
    BasicText(label, style = LocalTextStyle.current.merge(
        TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)), color = tint)
  }
}

/**
 * [ApprovedIcon] (Material `Icon`: no semantics, `ContentScale.Fit`, centred) whose tint is read while
 * drawing, so a tint change redraws it without recomposing or re-resolving the painter.
 */
@Composable
internal fun ApprovedDrawnTintIcon(name: String, tint: ColorProducer, size: Dp, modifier: Modifier = Modifier) {
  val painter = painterResource(approvedIconResource(name))
  val filters = remember { CcTintFilters() }
  Box(modifier.size(size).drawBehind {
    val place = ccFitCentered(painter.intrinsicSize, this.size)
    translate(place.left, place.top) {
      with(painter) { draw(Size(place.width, place.height), colorFilter = filters.of(tint())) }
    }
  })
}

/** `Modifier.semantics { selected = … }` whose value is observed from [selected] (no recomposition). */
internal fun Modifier.homeTabSelected(selected: State<Boolean>): Modifier = this then HomeTabSelectedElement(selected)

private data class HomeTabSelectedElement(val selected: State<Boolean>) : ModifierNodeElement<HomeTabSelectedNode>() {
  override fun create() = HomeTabSelectedNode(selected)
  override fun update(node: HomeTabSelectedNode) = node.update(selected)
  override fun InspectorInfo.inspectableProperties() {
    name = "homeTabSelected"; properties["selected"] = selected.value
  }
}

private class HomeTabSelectedNode(private var source: State<Boolean>) :
    Modifier.Node(), SemanticsModifierNode, ObserverModifierNode {
  private var isSelected = false

  override fun onAttach() { observe() }

  override fun onObservedReadsChanged() {
    val before = isSelected
    observe()
    if (isSelected != before) invalidateSemantics()
  }

  fun update(source: State<Boolean>) {
    if (source === this.source) return
    this.source = source
    if (isAttached) observe()
    invalidateSemantics()
  }

  private fun observe() { observeReads { isSelected = source.value } }

  override fun SemanticsPropertyReceiver.applySemantics() {
    selected = this@HomeTabSelectedNode.isSelected
  }
}
