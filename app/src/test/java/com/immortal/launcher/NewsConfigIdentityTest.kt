/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Actual subscription parsing/mutations against generated preferences; no feeds or HTTP. */
class NewsConfigIdentityTest {
  @Test fun missingNullAndInvalidIdsRemainStableAndReadOnly() = fixture(listOf(
      feed(null, "Missing"), feed(JSONObject.NULL, "Null"), feed("bad-id", "Invalid"), feed(0L, "Zero"))) {
    val before = NewsConfig.load(it.context)
    assertEquals(4, before.size); assertDistinct(before)
    repeat(4) { _ -> assertEquals(before, NewsConfig.load(it.context)) }
    assertEquals(0L, before.last().id); it.assertReadOnly()
  }

  @Test fun removingAMissingIdFeedRemovesOnlyTheChosenRow() = fixture(listOf(
      feed(null, "Legacy"), feed(5L, "Other"))) {
    val before = NewsConfig.load(it.context)
    NewsConfig.remove(it.context, before[0].id)
    assertEquals(listOf(before[1]), NewsConfig.load(it.context)); assertEquals(1, it.applies)
  }

  @Test fun removingFirstDuplicatePreservesSecondFeed() = fixture(duplicates()) {
    val before = NewsConfig.load(it.context)
    NewsConfig.remove(it.context, before[0].id)
    assertEquals(listOf(before[1]), NewsConfig.load(it.context)); assertEquals(1, it.applies)
  }

  @Test fun removingSecondDuplicatePreservesFirstFeed() = fixture(duplicates()) {
    val before = NewsConfig.load(it.context)
    NewsConfig.remove(it.context, before[1].id)
    assertEquals(listOf(before[0]), NewsConfig.load(it.context)); assertEquals(1, it.applies)
  }

  @Test fun replacementsReserveLaterValidIdsAndLongBoundaries() = fixture(listOf(
      feed(42L, "First"), feed(42L, "Duplicate"), feed(0L, "Zero"), feed(1L, "One"),
      feed(-1L, "Negative"), feed(Long.MIN_VALUE, "Minimum"), feed(Long.MAX_VALUE, "Maximum"))) {
    val loaded = NewsConfig.load(it.context); assertDistinct(loaded)
    for (index in listOf(0, 2, 3, 4, 5, 6))
      assertEquals(listOf(42L, 42L, 0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)[index], loaded[index].id)
    assertEquals(loaded, NewsConfig.load(it.context)); it.assertReadOnly()
  }

  @Test fun multipleDuplicateGroupsPreserveFieldsOrderAndFirstIds() = fixture(listOf(
      feed(-5L, "  A quoted ", " https://example.test/a?x=1&y=2 "), feed(-5L, "B"),
      feed(9L, "C"), feed(9L, "D"))) {
    val loaded = NewsConfig.load(it.context); assertDistinct(loaded)
    assertEquals(listOf("A quoted", "B", "C", "D"), loaded.map { f -> f.name })
    assertEquals("https://example.test/a?x=1&y=2", loaded.first().url)
    assertEquals(-5L, loaded[0].id); assertEquals(9L, loaded[2].id)
    assertEquals(loaded, NewsConfig.load(it.context)); it.assertReadOnly()
  }

  @Test fun identicalSavedRowsRemainIndependentlyRemovable() = fixture(listOf(
      feed(7L, "Same"), feed(7L, "Same"))) {
    val before = NewsConfig.load(it.context); assertEquals(2, before.size); assertDistinct(before)
    NewsConfig.remove(it.context, before[1].id)
    assertEquals(listOf(before[0]), NewsConfig.load(it.context))
  }

  @Test fun addPersistsStableNormalizedIdsAlongsideTheNewFeed() = fixture(duplicates()) {
    val before = NewsConfig.load(it.context)
    NewsConfig.add(it.context, " Added ", " https://example.test/added ")
    val after = NewsConfig.load(it.context)
    assertEquals(before, after.take(2)); assertDistinct(after)
    assertEquals("Added", after.last().name); assertEquals("https://example.test/added", after.last().url)
    assertEquals(after, NewsConfig.load(it.context)); assertEquals(1, it.applies)
  }

  @Test fun removalPersistsRemainingAssignedIdsWithoutRenumbering() = fixture(listOf(
      feed(42L, "First"), feed(42L, "Second"), feed(42L, "Third"))) {
    val before = NewsConfig.load(it.context)
    NewsConfig.remove(it.context, before[1].id)
    val after = NewsConfig.load(it.context)
    assertEquals(listOf(before[0], before[2]), after); assertDistinct(after)
    assertEquals(after, NewsConfig.load(it.context)); assertEquals(1, it.applies)
  }

  @Test fun uniqueStoredIdsIncludingBothLongBoundariesRemainExact() = fixture(listOf(
      feed(0L, "Zero"), feed(-1L, "Negative"), feed(Long.MIN_VALUE, "Minimum"), feed(Long.MAX_VALUE, "Maximum"))) {
    val loaded = NewsConfig.load(it.context)
    assertEquals(listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE), loaded.map { f -> f.id })
    assertEquals(loaded, NewsConfig.load(it.context)); it.assertReadOnly()
  }

  @Test fun blankUrlsRemainFilteredAndVisibleFieldsStayTrimmed() = fixture(listOf(
      feed(8L, "Blank", " "), feed(9L, "Empty", ""),
      feed(11L, " Visible ", " https://example.test/visible "))) {
    assertEquals(listOf(NewsConfig.Feed(11L, "Visible", "https://example.test/visible")), NewsConfig.load(it.context))
    NewsConfig.add(it.context, "Ignored", "  "); it.assertReadOnly()
  }

  @Test fun existingNumericStringIdsKeepTheirCompatibility() = fixture(listOf(
      feed("12", "Twelve"), feed("-9", "Minus nine"))) {
    assertEquals(listOf(12L, -9L), NewsConfig.load(it.context).map { f -> f.id }); it.assertReadOnly()
  }

  private fun duplicates() = listOf(feed(42L, "First", "https://example.test/first"),
      feed(42L, "Second", "https://example.test/second"))

  private fun feed(id: Any?, name: String, url: String = "https://example.test/generated") =
      JSONObject().put("name", name).put("url", url).apply { if (id != null) put("id", id) }

  private fun assertDistinct(feeds: List<NewsConfig.Feed>) {
    assertEquals("Each saved row needs a distinct identity", feeds.size, feeds.map { it.id }.toSet().size)
  }

  private fun fixture(rows: List<JSONObject>, operation: (Fixture) -> Unit) {
    val fixture = Fixture(rows)
    try { operation(fixture) }
    finally { assertTrue("Unexpected fixture operations: ${fixture.forbidden}", fixture.forbidden.isEmpty()) }
  }

  private class Fixture(rows: List<JSONObject>) {
    private val original = JSONArray().apply { rows.forEach { put(it) } }.toString()
    private var saved = original
    var edits = 0
    var applies = 0
    val forbidden = mutableListOf<String>()
    private fun refuse(message: String): Nothing {
      forbidden += message
      throw AssertionError(message)
    }
    private val prefs = Mockito.mock(SharedPreferences::class.java) { call ->
      when (call.method.name) {
        "getString" -> {
          if (call.getArgument<String>(0) != "feeds" || call.getArgument<Any?>(1) != null)
            refuse("Unexpected news feed preference read")
          saved
        }
        "edit" -> {
          edits++
          editor()
        }
        "toString" -> "GeneratedNewsPreferences"
        else -> refuse("Unexpected preferences method: ${call.method.name}")
      }
    }
    val context = Mockito.mock(Context::class.java) { call ->
      when (call.method.name) {
        "getSharedPreferences" -> {
          if (call.getArgument<String>(0) != "immortal_news" ||
              call.getArgument<Int>(1) != Context.MODE_PRIVATE) refuse("Unexpected preference store")
          prefs
        }
        "toString" -> "GeneratedNewsContext"
        else -> refuse("Unexpected Context method: ${call.method.name}")
      }
    }
    private fun editor(): SharedPreferences.Editor {
      var pending: String? = null
      return Mockito.mock(SharedPreferences.Editor::class.java) { call ->
        when (call.method.name) {
          "putString" -> {
            if (call.getArgument<String>(0) != "feeds") refuse("Unexpected saved key")
            pending = call.getArgument<String>(1)
            call.mock
          }
          "apply" -> {
            saved = pending ?: refuse("Missing news feed transaction value")
            applies++
            null
          }
          "toString" -> "GeneratedNewsEditor"
          else -> refuse("Unexpected editor method: ${call.method.name}")
        }
      }
    }
    fun assertReadOnly() {
      assertEquals(original, saved)
      assertEquals(0, edits)
      assertEquals(0, applies)
    }
  }
}
