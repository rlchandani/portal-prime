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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.concurrent.atomic.AtomicReference

/** Identity regressions exercised through the public SharedPreferences load/remove boundary. */
class CountdownIdentityTest {

  private data class Stored(
      val context: Context,
      val content: AtomicReference<String?>,
      val prefs: SharedPreferences,
      val editor: SharedPreferences.Editor,
  )

  private fun stored(raw: String?): Stored {
    val content = AtomicReference(raw)
    val context = mock(Context::class.java)
    val prefs = mock(SharedPreferences::class.java)
    val editor = mock(SharedPreferences.Editor::class.java)
    `when`(context.getSharedPreferences("immortal_countdown", Context.MODE_PRIVATE)).thenReturn(prefs)
    `when`(prefs.getString("events", null)).thenAnswer { content.get() }
    `when`(prefs.edit()).thenReturn(editor)
    var pending: String? = null
    `when`(editor.putString(eq("events"), anyString())).thenAnswer {
      pending = it.getArgument(1); editor
    }
    doAnswer { content.set(pending); null }.`when`(editor).apply()
    return Stored(context, content, prefs, editor)
  }

  private fun assertStableUnique(store: Stored, size: Int): List<CountdownConfig.Event> {
    val first = CountdownConfig.load(store.context)
    assertEquals(size, first.size)
    assertEquals("Every row must be independently addressable", size, first.map { it.id }.toSet().size)
    repeat(3) { assertEquals("Reload must preserve UI identities", first, CountdownConfig.load(store.context)) }
    return first
  }

  private val invalidIds = listOf("null", "true", "false", "\"\"", "\"not a number\"", "[]", "{}")

  @Test
  fun `missing ids are stable and unique even for identical rows`() {
    val store = stored("""[{"label":"same","month":6,"day":7},{"label":"same","month":6,"day":7}]""")
    val events = assertStableUnique(store, 2)
    assertEquals(events[0].copy(id = events[1].id), events[1])
  }

  @Test
  fun `invalid ids are repaired to stable unique identities`() {
    val rows = invalidIds.map { """{"id":$it,"label":"same"}""" }
    val store = stored((rows + """{"id":71,"label":"saved"}""").joinToString(",", "[", "]"))
    val events = assertStableUnique(store, rows.size + 1)
    assertEquals(71L, events.last().id)
    assertEquals("saved", events.last().label)
  }

  @Test
  fun `first remove of a missing id targets only that row and preserves saved neighbor`() {
    val store = stored("""[{"label":"legacy"},{"id":83,"label":"neighbor","emoji":"N","month":8,"day":9,"year":2030}]""")
    val first = CountdownConfig.load(store.context)
    assertEquals(2, first.size)
    CountdownConfig.remove(store.context, first[0].id)
    assertEquals(listOf(first[1]), CountdownConfig.load(store.context))
    assertEquals(83L, CountdownConfig.load(store.context).single().id)
  }

  @Test
  fun `first remove of each invalid id preserves the valid id neighbor`() {
    invalidIds.forEach { invalid ->
      val store = stored("""[{"id":$invalid,"label":"invalid"},{"id":97,"label":"neighbor"}]""")
      val first = CountdownConfig.load(store.context)
      assertEquals(2, first.size)
      CountdownConfig.remove(store.context, first[0].id)
      assertEquals("Invalid ID fixture: $invalid", listOf(first[1]), CountdownConfig.load(store.context))
      assertEquals(97L, CountdownConfig.load(store.context).single().id)
    }
  }

  @Test
  fun `duplicate ids keep first and later identical rows can be removed independently`() {
    val raw = """[
      {"id":109,"label":"same","month":2,"day":3},
      {"id":113,"label":"between"},
      {"id":109,"label":"same","month":2,"day":3},
      {"id":109,"label":"same","month":2,"day":3}
    ]"""
    val first = assertStableUnique(stored(raw), 4)
    assertEquals(109L, first[0].id)
    assertEquals(113L, first[1].id)
    for (target in listOf(2, 3)) {
      val store = stored(raw)
      CountdownConfig.remove(store.context, first[target].id)
      assertEquals(first.filterIndexed { index, _ -> index != target }, CountdownConfig.load(store.context))
      assertEquals(109L, CountdownConfig.load(store.context).first().id)
    }
  }

  @Test
  fun `repair reserves later saved id discovered through public load`() {
    val legacy = """{"label":"legacy","emoji":"L","month":4,"day":5,"year":2029}"""
    val candidate = CountdownConfig.load(stored("[$legacy]").context).single().id
    // Discover the collision without relying on a repair format, hash, or numeric range.
    val later = JSONObject().put("id", candidate).put("label", "saved later")
        .put("emoji", "S").put("month", 9).put("day", 10).put("year", 2031)
    val store = stored("[$legacy,$later]")
    val first = assertStableUnique(store, 2)
    assertEquals(CountdownConfig.Event(candidate, "saved later", "S", 9, 10, 2031), first[1])
    assertFalse(candidate == first[0].id)
    CountdownConfig.remove(store.context, first[0].id)
    assertEquals(listOf(first[1]), CountdownConfig.load(store.context))
  }

  @Test
  fun `all compatible optLong identities retain existing coercion and long extremes`() {
    val fixtures = listOf(
        "41" to 41L,
        "\"42\"" to 42L,
        "43.75" to 43L,
        "\"44.5\"" to 44L,
        "\"1e2\"" to 100L,
        "-5.9" to -5L,
        "0" to 0L,
        "-7" to -7L,
        "-9223372036854775808" to Long.MIN_VALUE,
        "9223372036854775807" to Long.MAX_VALUE,
        "\"-9223372036854775808\"" to Long.MIN_VALUE,
        "\"9223372036854775807\"" to Long.MAX_VALUE,
    )
    fixtures.forEach { (jsonId, expected) ->
      val store = stored("""[{"id":$jsonId,"label":"compatible"}]""")
      repeat(3) {
        assertEquals("Compatible ID fixture: $jsonId", expected, CountdownConfig.load(store.context).single().id)
      }
      CountdownConfig.remove(store.context, expected)
      assertTrue("Compatible ID must remain removable: $jsonId", CountdownConfig.load(store.context).isEmpty())
    }
  }

  @Test
  fun `load never writes even when repairing missing invalid or duplicate ids`() {
    val fixtures = listOf(
        """[{"label":"legacy"}]""",
        """[{"id":null},{"id":true}]""",
        """[{"id":127},{"id":127}]""",
        """[{"id":131,"label":"valid"}]""",
    )
    fixtures.forEach { raw ->
      val store = stored(raw)
      repeat(3) { CountdownConfig.load(store.context) }
      assertEquals(raw, store.content.get())
      verify(store.prefs, never()).edit()
      verifyNoInteractions(store.editor)
    }
  }

  @Test
  fun `removal persists all remaining repaired identities and fields`() {
    val store = stored("""[
      {"label":"missing","emoji":"M","month":3,"day":4,"year":2028},
      {"id":137,"label":"first"},
      {"id":137,"label":"duplicate","emoji":"D","month":7,"day":8},
      {"id":{},"label":"invalid"},
      {"id":139,"label":"remove me"}
    ]""")
    val first = CountdownConfig.load(store.context)
    assertEquals(5, first.size)
    val expected = first.dropLast(1)
    CountdownConfig.remove(store.context, 139L)
    val persisted = JSONArray(store.content.get()!!)
    assertEquals(expected.map { it.id }, (0 until persisted.length()).map {
      val row = persisted.getJSONObject(it)
      assertTrue("Repairs must be saved as numeric IDs", row.get("id") is Number)
      row.getLong("id")
    })
    repeat(3) { assertEquals(expected, CountdownConfig.load(store.context)) }
    // A fresh preference boundary must reconstruct the same saved identities.
    assertEquals(expected, CountdownConfig.load(stored(store.content.get()).context))
    verify(store.editor, times(1)).apply()
  }

  @Test
  fun `identity repair preserves fields array order and existing defaults`() {
    val store = stored("""[
      {"label":"  keep spaces  ","emoji":"X","month":12,"day":31,"year":2032},
      {"id":null},
      {"id":149,"label":"legacy values","emoji":"","month":15,"day":-2,"year":-10},
      {"id":"151","label":"last","month":2,"day":3}
    ]""")
    val events = assertStableUnique(store, 4)
    assertEquals(listOf(
        CountdownConfig.Event(0L, "  keep spaces  ", "X", 12, 31, 2032),
        CountdownConfig.Event(0L, "Event", "📅", 1, 1, 0),
        CountdownConfig.Event(0L, "legacy values", "", 15, -2, -10),
        CountdownConfig.Event(0L, "last", "📅", 2, 3, 0),
    ), events.map { it.copy(id = 0L) })
    assertEquals(149L, events[2].id)
    assertEquals(151L, events[3].id)
  }

  @Test
  fun `ordinary valid removal preserves neighbors and order`() {
    val store = stored("""[
      {"id":157,"label":"first","emoji":"A","month":1,"day":2,"year":2027},
      {"id":163,"label":"target"},
      {"id":167,"label":"last","emoji":"Z","month":11,"day":12,"year":2033}
    ]""")
    val first = CountdownConfig.load(store.context)
    assertEquals(3, first.size)
    CountdownConfig.remove(store.context, 163L)
    assertEquals(listOf(first[0], first[2]), CountdownConfig.load(store.context))
    verify(store.editor, times(1)).apply()
  }

  @Test
  fun `missing empty malformed and nonobject collections retain empty load fallback`() {
    val fixtures = listOf(
        null, "", "   ", "[]", "not json", "[{", "{}", "null", "42",
        "[null]", "[42]", "[true]", "[\"row\"]", "[[]]",
        """[{"id":173,"label":"valid before nonobject"},null]""",
    )
    fixtures.forEach { raw ->
      val store = stored(raw)
      repeat(3) { assertTrue("Existing empty fallback for: $raw", CountdownConfig.load(store.context).isEmpty()) }
      assertEquals(raw, store.content.get())
      verify(store.prefs, never()).edit()
      verifyNoInteractions(store.editor)
    }
  }
}
