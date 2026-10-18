/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.*

class RemotePresetsTest {
  private class Store(initial: Any? = null) {
    val content = AtomicReference(initial)
    val context = mock(Context::class.java)
    val prefs = mock(SharedPreferences::class.java)
    val editor = mock(SharedPreferences.Editor::class.java)
    var beforeWrite: () -> Unit = {}

    init {
      `when`(context.getSharedPreferences("remote_presets", Context.MODE_PRIVATE)).thenReturn(prefs)
      `when`(prefs.getString(eq("json"), nullable(String::class.java))).thenAnswer {
        val value = content.get()
        if (value == null) it.getArgument<String?>(1)
        else value as? String ?: throw ClassCastException("Synthetic stored value: $value")
      }
      `when`(prefs.edit()).thenReturn(editor)
      var pending: String? = null
      `when`(editor.putString(eq("json"), anyString())).thenAnswer {
        beforeWrite()
        pending = it.getArgument(1)
        editor
      }
      doAnswer { content.set(pending); null }.`when`(editor).apply()
    }
  }

  private val unreadable = listOf<Any>(
      true, 123, setOf("public-fixture"), "", " \t\r\n", "not-json", "[{", "{}", "null", "42",
      "[] garbage", "[]{}", "[][]", "[]\u0000", "[]\u0000hidden", "[]\u000b", "[]\u000c",
      "[]\u00a0", "[] // comment", "[] /* comment */", "[] # comment", "[] garbage]",
  )

  private fun unavailable(block: () -> Unit): IllegalStateException {
    val failure = assertThrows(IllegalStateException::class.java) { block() }
    assertEquals("Saved remote presets are unavailable", failure.message)
    assertNull("Parser errors may expose saved content", failure.cause)
    return failure
  }

  @Test fun missingKeyIsEmptyWithoutWriting() {
    val store = Store()
    assertEquals(0, RemotePresets.listJson(store.context).length())
    assertNull(RemotePresets.find(store.context, "missing"))
    assertNull(store.content.get())
    verify(store.prefs, never()).edit()
  }

  @Test fun emptyArrayWithJsonWhitespaceIsEmptyWithoutWriting() {
    val raw = " \t\r\n[] \t\r\n"
    val store = Store(raw)
    assertEquals(0, RemotePresets.listJson(store.context).length())
    assertNull(RemotePresets.find(store.context, "missing"))
    assertEquals(raw, store.content.get())
    verify(store.prefs, never()).edit()
  }

  @Test fun unreadableReadsThrowWithoutChangingStorage() {
    for (raw in unreadable) {
      val store = Store(raw)
      unavailable { RemotePresets.listJson(store.context) }
      assertEquals(raw, store.content.get())
      verify(store.prefs, never()).edit()
      verifyNoInteractions(store.editor)
    }
  }

  @Test fun unreadableFindThrowsWithoutChangingStorage() {
    for (raw in unreadable) {
      val store = Store(raw)
      unavailable { RemotePresets.find(store.context, "public-fixture") }
      assertEquals(raw, store.content.get())
      verify(store.prefs, never()).edit()
    }
  }

  @Test fun unreadableSaveRejectsReplacementBeforeOpeningEditor() {
    for (raw in unreadable) {
      val store = Store(raw)
      unavailable { RemotePresets.save(store.context, JSONArray()) }
      assertEquals(raw, store.content.get())
      verify(store.prefs, never()).edit()
      verifyNoInteractions(store.editor)
    }
  }

  @Test fun missingAndEmptyStoresCanBeSaved() {
    for (raw in listOf(null, "[]")) {
      val store = Store(raw)
      RemotePresets.save(store.context, JSONArray("""[{"id":"new","steps":[]}]"""))
      assertEquals("new", RemotePresets.find(store.context, "new")!!.getString("id"))
      assertEquals(1, RemotePresets.listJson(store.context).length())
    }
  }

  @Test fun saveValidatesTheCurrentStoredValueEveryTime() {
    val store = Store("[]")
    RemotePresets.save(store.context, JSONArray("""[{"id":"first"}]"""))
    store.content.set("public-fixture-corruption")
    unavailable { RemotePresets.save(store.context, JSONArray()) }
    assertEquals("public-fixture-corruption", store.content.get())
    verify(store.editor, times(1)).apply()
  }

  @Test fun legacyRowsAndUnknownFieldsSurviveReadAndSave() {
    val raw = """[null,3,true,"legacy",[],{}, {"id":7,"name":false,"steps":"legacy","future":{"x":[1,2]}}, {"id":"same","name":"first"}, {"id":"same","name":"second"}]"""
    val store = Store(raw)
    val rows = RemotePresets.listJson(store.context)
    assertEquals(9, rows.length())
    assertEquals("first", RemotePresets.find(store.context, "same")!!.getString("name"))
    assertEquals("legacy", RemotePresets.find(store.context, "7")!!.getString("steps"))
    assertNotNull(RemotePresets.find(store.context, ""))
    assertNull(RemotePresets.find(store.context, "absent"))
    assertEquals(raw, store.content.get())
    RemotePresets.save(store.context, rows)
    assertEquals(JSONArray(raw).toString(), RemotePresets.listJson(store.context).toString())
    assertEquals(2, RemotePresets.find(store.context, "7")!!.getJSONObject("future").getJSONArray("x").getInt(1))
  }

  @Test fun quotedBracketsEscapesAndNestedArraysAreReadable() {
    val raw = """[{"id":"quoted","name":"bracket ] and [ and escaped \" quote","future":[{"value":"]"}]}]""" + " \n"
    val store = Store(raw)
    assertEquals("]", RemotePresets.find(store.context, "quoted")!!.getJSONArray("future").getJSONObject(0).getString("value"))
    assertEquals(raw, store.content.get())
  }

  @Test fun lenientLegacyArrayParsingIsRetained() {
    val store = Store("[{'id':'legacy','future':'[ ]'}]")
    assertEquals("[ ]", RemotePresets.find(store.context, "legacy")!!.getString("future"))
  }

  private fun assertWaitsForSave(operation: (Store) -> Unit) {
    val store = Store("""[{"id":"old"}]""")
    val writing = CountDownLatch(1)
    val release = CountDownLatch(1)
    val started = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    val firstWrite = AtomicBoolean(true)
    store.beforeWrite = {
      if (firstWrite.compareAndSet(true, false)) {
        writing.countDown()
        check(release.await(5, TimeUnit.SECONDS))
      }
    }
    try {
      val saving = executor.submit { RemotePresets.save(store.context, JSONArray("""[{"id":"new"}]""")) }
      assertTrue(writing.await(5, TimeUnit.SECONDS))
      val overlapping = executor.submit { started.countDown(); operation(store) }
      assertTrue(started.await(5, TimeUnit.SECONDS))
      try {
        overlapping.get(150, TimeUnit.MILLISECONDS)
        fail("Store operation must wait until the in-progress replacement completes")
      } catch (_: TimeoutException) {
        // The operation cannot observe or replace storage during another save.
      }
      release.countDown()
      saving.get(5, TimeUnit.SECONDS)
      overlapping.get(5, TimeUnit.SECONDS)
    } finally {
      release.countDown()
      executor.shutdownNow()
      executor.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test fun listWaitsForSaveThenSeesReplacement() = assertWaitsForSave {
    assertEquals("new", RemotePresets.listJson(it.context).getJSONObject(0).getString("id"))
  }

  @Test fun findWaitsForSaveThenSeesReplacement() = assertWaitsForSave {
    assertNotNull(RemotePresets.find(it.context, "new"))
  }

  @Test fun saveWaitsForAnotherSave() = assertWaitsForSave {
    RemotePresets.save(it.context, JSONArray())
    assertEquals("[]", it.content.get())
  }
}
