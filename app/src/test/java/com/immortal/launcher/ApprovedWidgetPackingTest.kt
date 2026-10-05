/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ApprovedWidgetPacking.Placement
import com.immortal.launcher.ApprovedWidgetPacking.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ApprovedWidgetPackingTest {
  @Test fun heroWithFourSmallWidgets_fillsRightHandTwoByTwoGroup() {
    assertEquals(
        listOf(
            Placement(0, 0, 0, 2, 4),
            Placement(1, 2, 0, 1, 2),
            Placement(2, 3, 0, 1, 2),
            Placement(3, 2, 2, 1, 2),
            Placement(4, 3, 2, 1, 2),
        ),
        ApprovedWidgetPacking.pack(listOf(Span(2, 4)) + List(4) { Span(1, 2) }),
    )
  }

  @Test fun shortNeighborOfTallWidget_stacksInSameColumnsAtRowOne() {
    assertEquals(
        listOf(
            Placement(0, 0, 0, 2, 4),
            Placement(1, 2, 0, 2, 1),
            Placement(2, 2, 1, 2, 1),
        ),
        ApprovedWidgetPacking.pack(listOf(Span(2, 4), Span(2, 1), Span(2, 1))),
    )
  }

  @Test fun earliestRowWinsEvenWhenLaterRowHasLeftmostColumnFree() {
    assertEquals(
        listOf(
            Placement(0, 0, 0, 2, 1),
            Placement(1, 2, 0, 1, 4),
            Placement(2, 3, 0, 1, 1),
        ),
        ApprovedWidgetPacking.pack(listOf(Span(2, 1), Span(1, 4), Span(1, 1))),
    )
  }

  @Test fun fullWidthBarrier_blocksTallRectangleButAllowsEarlierShortHole() {
    assertEquals(
        listOf(
            Placement(0, 0, 0, 2, 1),
            Placement(1, 2, 0, 2, 2),
            Placement(2, 0, 2, 4, 1),
            Placement(3, 0, 3, 2, 2),
            Placement(4, 0, 1, 2, 1),
        ),
        ApprovedWidgetPacking.pack(
            listOf(Span(2, 1), Span(2, 2), Span(4, 1), Span(2, 2), Span(2, 1))),
    )
  }

  @Test fun invalidSpans_areNormalizedWithoutChangingInput() {
    val spans = listOf(Span(Int.MIN_VALUE, 0), Span(Int.MAX_VALUE, Int.MAX_VALUE), Span(0, -7))
    val original = spans.toList()
    val packed = ApprovedWidgetPacking.pack(spans)
    assertEquals(
        listOf(
            Placement(0, 0, 0, 1, 1),
            Placement(1, 0, 1, 4, 4),
            Placement(2, 1, 0, 1, 1),
        ),
        packed,
    )
    assertEquals(original, spans)
  }

  @Test fun customColumnCount_controlsWidthNormalizationAndWrapping() {
    assertEquals(
        listOf(
            Placement(0, 0, 0, 3, 1),
            Placement(1, 0, 1, 2, 2),
            Placement(2, 2, 1, 1, 2),
        ),
        ApprovedWidgetPacking.pack(listOf(Span(8, 1), Span(2, 2), Span(1, 2)), columns = 3),
    )
  }

  @Test fun oneColumn_stacksNormalizedRectanglesVertically() {
    assertEquals(
        listOf(
            Placement(0, 0, 0, 1, 4),
            Placement(1, 0, 4, 1, 1),
            Placement(2, 0, 5, 1, 2),
        ),
        ApprovedWidgetPacking.pack(listOf(Span(8, 8), Span(-1, -1), Span(1, 2)), columns = 1),
    )
  }

  @Test fun sixtyFourColumns_handlesSignBitAndFullWidthMask() {
    assertEquals(
        listOf(
            Placement(0, 0, 0, 63, 4),
            Placement(1, 63, 0, 1, 1),
            Placement(2, 63, 1, 1, 1),
            Placement(3, 0, 4, 64, 1),
            Placement(4, 63, 2, 1, 2),
        ),
        ApprovedWidgetPacking.pack(
            listOf(Span(63, 4), Span(1, 1), Span(1, 1), Span(64, 1), Span(1, 2)),
            columns = 64),
    )
  }

  @Test fun emptyInput_isEmptyOnEveryCall() {
    repeat(3) {
      assertTrue(ApprovedWidgetPacking.pack(emptyList()).isEmpty())
      assertTrue(ApprovedWidgetPacking.pack(emptyList(), columns = 64).isEmpty())
    }
  }

  @Test fun repeatedCalls_areStableAndDoNotRetainPriorOccupancy() {
    val spans = listOf(Span(2, 4), Span(2, 1), Span(1, 2), Span(4, 3), Span(1, 1))
    val first = ApprovedWidgetPacking.pack(spans)
    repeat(20) {
      ApprovedWidgetPacking.pack(List(30) { Span(4, 4) })
      assertEquals(first, ApprovedWidgetPacking.pack(spans))
    }
  }

  @Test fun duplicateSpans_preserveEveryInputIndexExactlyOnce() {
    val spans = List(100) { Span(1, 2) }
    val packed = ApprovedWidgetPacking.pack(spans)
    assertEquals(spans.indices.toList(), packed.map { it.index })
    assertEquals(spans.size, packed.map { it.index }.toSet().size)
    assertValidPacking(spans, packed, 4)
  }

  @Test fun mixedSpans_haveNoCollisionsAndAlwaysChooseFirstAvailablePosition() {
    val random = Random(2026)
    for (columns in 1..8) {
      val spans = List(48) { Span(random.nextInt(-2, 11), random.nextInt(-2, 7)) }
      val packed = ApprovedWidgetPacking.pack(spans, columns)
      assertValidPacking(spans, packed, columns)
      packed.forEachIndexed { index, placement ->
        val previous = packed.take(index)
        for (row in 0..placement.row) {
          val lastColumn = if (row == placement.row) placement.column - 1
              else columns - placement.columns
          for (column in 0..lastColumn) {
            val earlier = placement.copy(column = column, row = row)
            assertTrue(
                "Index $index skipped available position ($column, $row) in $columns columns",
                previous.any { overlaps(earlier, it) },
            )
          }
        }
      }
    }
  }

  @Test fun manyFullWidthWidgets_growStorageWithoutLosingEarlierOccupancy() {
    val spans = List(128) { Span(4, 4) } + Span(1, 1)
    val packed = ApprovedWidgetPacking.pack(spans)
    assertEquals(Placement(127, 0, 508, 4, 4), packed[127])
    assertEquals(Placement(128, 0, 512, 1, 1), packed[128])
    assertValidPacking(spans, packed, 4)
  }

  @Test fun unsupportedColumnCounts_areRejectedEvenForEmptyInput() {
    for (columns in listOf(Int.MIN_VALUE, -1, 0, 65, Int.MAX_VALUE)) {
      for (spans in listOf(emptyList(), listOf(Span(1, 1)))) {
        var rejected = false
        try {
          ApprovedWidgetPacking.pack(spans, columns)
        } catch (_: IllegalArgumentException) {
          rejected = true
        }
        assertTrue("Accepted column count $columns", rejected)
      }
    }
  }

  private fun assertValidPacking(spans: List<Span>, packed: List<Placement>, columns: Int) {
    assertEquals(spans.indices.toList(), packed.map { it.index })
    packed.forEachIndexed { index, placement ->
      assertEquals(spans[index].columns.coerceIn(1, columns), placement.columns)
      assertEquals(spans[index].rows.coerceIn(1, 4), placement.rows)
      assertTrue(placement.column >= 0)
      assertTrue(placement.row >= 0)
      assertTrue(placement.column + placement.columns <= columns)
      for (other in packed.take(index)) {
        assertFalse("Indices ${other.index} and $index overlap", overlaps(placement, other))
      }
    }
  }

  private fun overlaps(a: Placement, b: Placement): Boolean =
      a.column < b.column + b.columns && b.column < a.column + a.columns &&
          a.row < b.row + b.rows && b.row < a.row + a.rows
}
