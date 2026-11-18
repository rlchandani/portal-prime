/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected

/**
 * The Control Center's screen-reader shape. The Portal always runs accessibility services (Meta's),
 * so Compose walks and diffs the whole semantics tree after every change; each extra node costs
 * main-thread time between frames. Every tile, row, pill and tab is therefore ONE node that says
 * everything a listener needs ([ccSpeak]); its icons and texts are not separate nodes.
 *
 * Put [ccSpeak] AFTER `clickable` in the chain: clearing drops semantics declared inside it (the
 * child texts) but keeps the click action and role declared before it.
 *
 * Unlike `clearAndSetSemantics { }` (a fresh lambda on every call, so every recomposition would
 * re-announce the node and re-run the tree diff) the element is compared by value: an unchanged
 * description is no semantics change at all.
 */
internal fun Modifier.ccSpeak(description: String, selected: Boolean? = null, heading: Boolean = false): Modifier =
    this then CcSpeakElement(description, selected, heading)

/** Decorative content: no node at all (the parent already says it). */
internal fun Modifier.ccSilent(): Modifier = this then CcSpeakElement(null, null, false)

private data class CcSpeakElement(val description: String?, val selected: Boolean?, val heading: Boolean) : ModifierNodeElement<CcSpeakNode>() {
  override fun create() = CcSpeakNode(description, selected, heading)

  override fun update(node: CcSpeakNode) {
    if (node.description == description && node.selected == selected && node.heading == heading) return
    node.description = description; node.selected = selected; node.heading = heading
    node.invalidateSemantics()
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "ccSpeak"; properties["description"] = description; properties["selected"] = selected; properties["heading"] = heading
  }
}

private class CcSpeakNode(var description: String?, var selected: Boolean?, var heading: Boolean) : Modifier.Node(), SemanticsModifierNode {
  override val shouldClearDescendantSemantics: Boolean get() = true

  override fun SemanticsPropertyReceiver.applySemantics() {
    // Read the node's fields explicitly: inside this receiver `selected` / `heading` name the
    // semantics properties (whose getters throw), not the fields.
    val node = this@CcSpeakNode
    node.description?.let { contentDescription = it }
    node.selected?.let { selected = it }
    if (node.heading) heading()
  }
}

/** Screen-reader text for the overview's pieces (pure, so the wording is tested). */
internal object CcSpeech {
  /** Header pills: who's home. */
  fun people(homeNames: List<String>, everyone: Int): String = when {
    everyone == 0 -> ""
    homeNames.isEmpty() -> "Nobody home"
    else -> "${homeNames.size} home: ${homeNames.joinToString(", ")}"
  }

  fun alarm(label: String): String = "Alarm $label"

  /** The plan legend for its lens, e.g. "Legend: 4 lights on, amber open, blue motion, green locked". */
  fun legend(l: CcLegend): String = "Legend: " + when (l.lens) {
    CcLens.LIVE -> "${l.lightsOn} ${if (l.lightsOn == 1) "light" else "lights"} on, amber open, blue motion, green locked"
    CcLens.CLIMATE -> listOfNotNull("blue ${l.cold} to orange ${l.warm}", l.climate).joinToString(", ")
    CcLens.ENERGY -> "solar, battery, grid and rooms by colour"
  }

  /**
   * The plan canvas as one node: floor, lens, then each room with the caption drawn under its
   * name ("Kitchen, 71°, 1 light on"), and what a tap does. It changes only when a caption a
   * listener would hear changes, not on every Home Assistant tick.
   */
  fun plan(floor: String, lens: CcLens, rooms: List<Pair<String, String>>): String {
    val view = when (lens) { CcLens.LIVE -> "live view"; CcLens.CLIMATE -> "climate view"; CcLens.ENERGY -> "energy view" }
    val list = rooms.joinToString(". ") { (name, caption) -> if (caption.isBlank()) name else "$name, ${caption.replace(" · ", ", ")}" }
    return "$floor floor plan, $view. $list. Tap a room to open it"
  }

  /** A scene tile: "Good night, scene. Turns 8 things off". */
  fun scene(name: String, subtitle: String): String = if (subtitle.isBlank()) name else "$name. $subtitle"

  /** Energy now card, from the values shown on it ("1.2" kW strings, "80%", or "—" for unknown). */
  fun energyNow(home: String, solar: String, battery: String, grid: String): String =
      "Energy now: using ${kw(home)}, solar ${kw(solar)}, battery ${known(battery)}, grid ${kw(grid)}. Opens Energy"

  private fun known(v: String): String = if (v == UNKNOWN) "unknown" else v
  private fun kw(v: String): String = if (v == UNKNOWN) "unknown" else "$v kilowatts"
  private const val UNKNOWN = "—"

  /** An attention row: "Garage door open. Open for 12 min". */
  fun row(title: String, body: String): String = if (body.isBlank()) title else "$title. $body"
}
