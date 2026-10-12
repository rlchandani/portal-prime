/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class CalendarOccurrenceSelectionTest {
  @Test fun sortedLargeInputConstructsOnlyReturnedValues() {
    var constructions = 0
    val selection = CalendarOccurrenceSelection<Int>(500)
    repeat(20_000) { i -> selection.offer(i.toLong()) { constructions++; i } }
    assertEquals((0 until 500).toList(), selection.result())
    assertEquals(500, constructions)
  }
  @Test fun tiedLateValuesAreNeverConstructed() {
    val selection = CalendarOccurrenceSelection<String>(3)
    listOf("first", "second", "third").forEach { value -> selection.offer(5) { value } }
    repeat(1000) { selection.offer(5) { fail("late equal timestamp must be rejected"); "late" } }
    assertEquals(listOf("first", "second", "third"), selection.result())
  }
  @Test fun discardedLaterValueDoesNotRunItsFactory() {
    val selection = CalendarOccurrenceSelection<String>(2)
    selection.offer(4) { "four" }; selection.offer(2) { "two" }
    selection.offer(6) { throw IllegalStateException("discarded factory") }
    assertEquals(listOf("two", "four"), selection.result())
  }
  @Test fun equalTimeEvictionKeepsTheFirstEncounteredValues() {
    val selection = CalendarOccurrenceSelection<String>(3)
    selection.offer(10) { "first ten" }; selection.offer(10) { "second ten" }
    selection.offer(10) { "third ten" }; selection.offer(5) { "five" }
    assertEquals(listOf("five", "first ten", "second ten"), selection.result())
  }
  @Test fun arbitraryInputReturnsStableEarliestResults() {
    val selection = CalendarOccurrenceSelection<String>(4)
    listOf(8L to "eight", 3L to "first three", 5L to "five", 3L to "second three",
        2L to "two", 3L to "third three", 9L to "nine").forEach { (time, title) ->
      selection.offer(time) { title }
    }
    assertEquals(listOf("two", "first three", "second three", "third three"), selection.result())
  }
  @Test fun descendingLargeInputKeepsEarliestFiveHundred() {
    val selection = CalendarOccurrenceSelection<Int>(500)
    for (i in 19_999 downTo 0) selection.offer(i.toLong()) { i }
    assertEquals((0 until 500).toList(), selection.result())
  }
  @Test fun failedReplacementDoesNotDiscardExistingValue() {
    val selection = CalendarOccurrenceSelection<String>(2)
    selection.offer(10) { "ten" }; selection.offer(20) { "twenty" }
    try { selection.offer(5) { throw IllegalStateException("fixture") }; fail("failure required") }
    catch (e: IllegalStateException) { assertEquals("fixture", e.message) }
    assertEquals(listOf("ten", "twenty"), selection.result())
  }
  @Test fun repeatedResultsAndNewOffersRemainIndependent() {
    val selection = CalendarOccurrenceSelection<String>(2)
    selection.offer(10) { "ten" }; selection.offer(20) { "twenty" }
    val first = selection.result(); (first as MutableList).clear()
    selection.offer(5) { "five" }
    assertEquals(listOf("five", "ten"), selection.result())
  }
  @Test fun emptyAndPartiallyFilledInputsWork() {
    val selection = CalendarOccurrenceSelection<String>(500)
    assertTrue(selection.result().isEmpty())
    selection.offer(2) { "two" }; selection.offer(1) { "one" }
    assertEquals(listOf("one", "two"), selection.result())
  }
  @Test fun extremeTimestampsDoNotOverflowComparator() {
    val selection = CalendarOccurrenceSelection<Long>(2)
    listOf(Long.MAX_VALUE, 0L, Long.MIN_VALUE).forEach { v -> selection.offer(v) { v } }
    assertEquals(listOf(Long.MIN_VALUE, 0L), selection.result())
  }
  @Test fun separateReadersDoNotShareMutableSelection() {
    val first = CalendarOccurrenceSelection<String>(1)
    val second = CalendarOccurrenceSelection<String>(1)
    first.offer(0) { "first" }; second.offer(0) { "second" }
    assertEquals(listOf("first"), first.result()); assertEquals(listOf("second"), second.result())
  }
  @Test fun randomizedInputsMatchStableReferenceSelection() {
    val random = java.util.Random(52)
    repeat(20) {
      val input = List(1000) { i -> random.nextInt(50).toLong() to i }
      val selection = CalendarOccurrenceSelection<Int>(37)
      input.forEach { (start, value) -> selection.offer(start) { value } }
      // Kotlin's stable sort is the independent pre-optimization contract.
      assertEquals(input.sortedBy { it.first }.take(37).map { it.second }, selection.result())
    }
  }
  @Test fun calendarCapKeepsFirstFiveHundredEqualTimeEvents() = utc {
    val source = (0 until 600).joinToString("\n") { event("20261012T100000Z", "20261012T110000Z", "item$it") }
    assertEquals((0 until 500).map { "item$it" }, CalendarFeed.parse(source, now, 45).map { it.title })
  }
  @Test fun lateEarlierCalendarEventReplacesLastEqualTimeEvent() = utc {
    val source = (0 until 500).joinToString("\n") { event("20261012T100000Z", "20261012T110000Z", "item$it") } +
        "\n" + event("20261012T090000Z", "20261012T110000Z", "earlier")
    assertEquals(listOf("earlier") + (0 until 499).map { "item$it" }, CalendarFeed.parse(source, now, 45).map { it.title })
  }
  @Test fun recurrenceCapPreservesFeedOrderAcrossEqualDates() = utc {
    val source = (0 until 20).joinToString("\n") { event("20261012T100000Z", "20261012T110000Z", "series$it", "FREQ=DAILY;COUNT=45") }
    val rows = CalendarFeed.parse(source, now, 45)
    assertEquals(500, rows.size)
    assertEquals(List(25) { (0 until 20).map { "series$it" } }.flatten(), rows.map { it.title })
    assertEquals(instant("20261105T100000Z"), rows.last().startMillis)
  }
  @Test fun filteringOccursBeforeFiveHundredResultCap() = utc {
    val past = event("20200101T100000Z", "20200101T110000Z", "past")
    val future = event("20270101T100000Z", "20270101T110000Z", "future")
    val valid = event("20261012T100000Z", "20261012T110000Z", "valid")
    assertEquals(listOf("valid"), CalendarFeed.parse(past.repeat(501)+future.repeat(501)+valid, now, 45).map { it.title })
  }
  @Test fun warmReadUsesFreshTimeAndHorizonForBoundedResults() = utc {
    val source = (0 until 20).joinToString("\n") { event("20261012T100000Z", "20261012T110000Z", "series$it", "FREQ=DAILY;COUNT=45") }
    assertEquals(500, CalendarFeed.parse(source, now, 45).size)
    val later = CalendarFeed.parse(source, now+40*86_400_000L, 2)
    assertEquals(40, later.size)
    assertEquals(instant("20261121T100000Z"), later.first().startMillis)
  }
  private val now = 1_791_792_000_000L
  private fun event(start: String, end: String, title: String, rule: String? = null): String =
      "BEGIN:VEVENT\nDTSTART:$start\nDTEND:$end\nSUMMARY:$title\n" +
          (rule?.let { "RRULE:$it\n" } ?: "") + "END:VEVENT\n"
  private fun instant(value: String): Long = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
  }.parse(value)!!.time
  private fun utc(block: () -> Unit) {
    val zone = TimeZone.getDefault(); val locale = Locale.getDefault()
    val format = Locale.getDefault(Locale.Category.FORMAT); val display = Locale.getDefault(Locale.Category.DISPLAY)
    try { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); Locale.setDefault(Locale.US); block() }
    finally { TimeZone.setDefault(zone); Locale.setDefault(locale)
      Locale.setDefault(Locale.Category.FORMAT, format); Locale.setDefault(Locale.Category.DISPLAY, display) }
  }
}
