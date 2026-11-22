/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.ceil

/**
 * Where the Calls header's controls go below its title row (pure, so it is JVM-tested).
 *
 * [LayoutClass.LARGE] (the Portal+) returns null: the shipped single-row header, untouched.
 * Compact windows (the Mini either way up) never scroll sideways and never clip: the controls are
 * packed in their reading order (tabs, the measurement-only test pills, then the Messenger &
 * WhatsApp hand-off) into as many full-width rows as the window needs. A row holding the tabs lets
 * the tabs take the width the other items leave; a row without them shares the width equally.
 *  - Mini upright (800 dp): the tabs span the content width, the hand-off is a full-width row below.
 *  - Mini turned (1280 dp): one row, the tabs stretching to the hand-off pill at the end.
 */
internal object CallsHeaderLayout {
  enum class Item { TABS, LOOPBACK_TEST, TEST_MESSAGE, HANDOFF }

  /** Labels as the pills show them (the spoken names stay the same in every layout). */
  const val HANDOFF_LABEL = "Messenger & WhatsApp"
  const val LOOPBACK_LABEL = "Loopback test"
  const val TEST_MESSAGE_LABEL = "Test message"

  /** Compact header side padding (each side) and the gap between items in a row, dp. */
  const val SIDE_PADDING_DP = 32
  const val GAP_DP = 16

  // Width estimates in dp at font scale 1: a generous per-character width for each type size plus
  // the control's fixed parts. Overestimating only moves an item to the next row; never clips.
  private const val TAB_CHAR_DP = 15.5 // 25.5 sp semibold
  private const val TAB_CHROME_DP = 68.0 // 34 dp padding each side
  private const val TABS_TRACK_DP = 12.0 // 6 dp track padding each side
  private const val PILL_CHAR_DP = 14.5 // 24 sp semibold (CallButtonSize.SM)
  private const val PILL_CHROME_DP = 102.0 // 30 dp padding each side + 28 dp icon + 14 dp gap

  /**
   * The rows of controls under the title row, or null for [LayoutClass.LARGE].
   * [widthDp] is the window width, [tabLabels] the tab labels as shown (with any "· n" counts).
   */
  fun rows(cls: LayoutClass, widthDp: Int, tabLabels: List<String>, testPills: Boolean,
           fontScale: Float = 1f): List<List<Item>>? {
    if (!cls.compact) return null
    val content = (widthDp - 2 * SIDE_PADDING_DP).toDouble()
    val items = listOfNotNull(Item.TABS, Item.LOOPBACK_TEST.takeIf { testPills },
        Item.TEST_MESSAGE.takeIf { testPills }, Item.HANDOFF)
    val rows = mutableListOf<List<Item>>()
    var row = listOf<Item>()
    var used = 0.0
    for (item in items) {
      val w = widthOf(item, tabLabels, fontScale)
      val needed = if (row.isEmpty()) w else used + GAP_DP + w
      if (row.isEmpty() || needed <= content) {
        row = row + item
        used = needed
      } else {
        rows += row
        row = listOf(item)
        used = w
      }
    }
    if (row.isNotEmpty()) rows += row
    return rows
  }

  /** The width the tabs stretch to in [row]: the content width less the row's other items and gaps. */
  fun tabsTrackDp(row: List<Item>, widthDp: Int, tabLabels: List<String>, fontScale: Float = 1f): Double {
    val others = row.filter { it != Item.TABS }
    return widthDp - 2.0 * SIDE_PADDING_DP - others.sumOf { widthOf(it, tabLabels, fontScale) + GAP_DP }
  }

  /**
   * Weights for the stretched tabs: equal (the calm look) when every label fits an equal share of
   * [trackDp]; otherwise each tab's natural width, so a long "Messages · 12" never gets cut.
   */
  fun tabWeights(tabLabels: List<String>, trackDp: Double, fontScale: Float = 1f): List<Float> {
    val natural = tabLabels.map { ceil(it.length * TAB_CHAR_DP * fontScale) + TAB_CHROME_DP }
    val share = (trackDp - TABS_TRACK_DP) / tabLabels.size.coerceAtLeast(1)
    return if (natural.all { it <= share }) natural.map { 1f } else natural.map { it.toFloat() }
  }

  /** Estimated natural width of [item], dp. */
  fun widthOf(item: Item, tabLabels: List<String>, fontScale: Float = 1f): Double = when (item) {
    Item.TABS -> TABS_TRACK_DP + tabLabels.sumOf { ceil(it.length * TAB_CHAR_DP * fontScale) + TAB_CHROME_DP }
    Item.HANDOFF -> pill(HANDOFF_LABEL, fontScale)
    Item.LOOPBACK_TEST -> pill(LOOPBACK_LABEL, fontScale)
    Item.TEST_MESSAGE -> pill(TEST_MESSAGE_LABEL, fontScale)
  }

  private fun pill(label: String, fontScale: Float) = ceil(label.length * PILL_CHAR_DP * fontScale) + PILL_CHROME_DP
}
