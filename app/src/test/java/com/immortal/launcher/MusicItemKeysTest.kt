/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MusicItemKeysTest {
  private data class Row(val id: String, val title: String = "")
  private fun keys(vararg ids: String) = musicItemKeys(ids.map { Row(it) }) { it.id }

  @Test fun repeatedSongsHaveDistinctRowIdentities() {
    val result = keys("repeat", "other", "repeat", "repeat", "other")
    assertEquals(5, result.size)
    assertEquals(5, result.toSet().size)
  }

  @Test fun providerIdsCannotCollideWithStaticHeaderKeys() {
    val headers = listOf("hero", "track-heading", "message", "heading", "no-results")
    val result = musicItemKeys(headers) { it }
    assertTrue(result.none { it in headers })
  }

  @Test fun missingIdsCannotCollideWithRealIdsOrEachOther() {
    val result = keys("", "missing:0", " ", "missing:2", "", "id:missing:0:0")
    assertEquals(result.size, result.toSet().size)
  }

  @Test fun unrelatedInsertionsAndReorderingPreserveIdentifiableRowKeys() {
    val original = keys("a", "b", "a", "c")
    val inserted = keys("new", "a", "b", "a", "c")
    assertEquals(original, inserted.drop(1))
    val reordered = keys("c", "a", "b", "a")
    assertEquals(original[3], reordered[0])
    assertEquals(original[0], reordered[1])
    assertEquals(original[1], reordered[2])
    assertEquals(original[2], reordered[3])
  }

  @Test fun metadataUpdatesLeaveKeysAndBackendIdsUntouched() {
    val original = listOf(Row("repeat", "First"), Row("repeat", "Second"))
    val renamed = original.map { it.copy(title = "New ${it.title}") }
    assertEquals(musicItemKeys(original) { it.id }, musicItemKeys(renamed) { it.id })
    assertEquals(listOf("repeat", "repeat"), original.map { it.id })
  }

  @Test fun keysHandleUnicodeAndDelimiterLikeIdsWithoutAmbiguity() {
    val result = keys("曲目", "曲目", "item:4:test:0", "id:a:1", "a", "a:1", "a")
    assertEquals(result.size, result.toSet().size)
  }

  @Test fun largeRepeatedListsResolveEachIdExactlyOnce() {
    val input = List(10_000) { Row("track-${it % 7}") }
    var reads = 0
    val result = musicItemKeys(input) { reads++; it.id }
    assertEquals(input.size, reads)
    assertEquals(input.size, result.toSet().size)
  }

  @Test fun anEmptyListDoesNotResolveAnyIds() {
    val result = musicItemKeys(emptyList<Row>()) {
      fail("No item ID should be read")
      it.id
    }
    assertTrue(result.isEmpty())
  }
}
