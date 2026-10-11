/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class NativeFilmstripSelectionTest {
  private val sources = (0 until 20).map { "source-$it" }
  @Test fun selectionPreservesSchedulerOrder() { assertEquals(listOf("source-9","source-2","source-0"),NativeFilmstripSelection.resolve(listOf(9,2,0),sources)) }
  @Test fun duplicateSelectionIsRejected() { assertNull(NativeFilmstripSelection.resolve(listOf(1,1),sources)) }
  @Test fun outOfAlbumIndicesAreRejected() { assertNull(NativeFilmstripSelection.resolve(listOf(20),sources)); assertNull(NativeFilmstripSelection.resolve(listOf(-1),sources)) }
  @Test fun emptySelectionIsRejected() { assertNull(NativeFilmstripSelection.resolve(emptyList(),sources)) }
  @Test fun selectionIsBoundedToTwelve() { assertNull(NativeFilmstripSelection.resolve((0..12).toList(),sources)); assertEquals(12,NativeFilmstripSelection.resolve((0..11).toList(),sources)!!.size) }
  @Test fun sceneTokenIsBoundedAndNotJavascript() { assertTrue(NativeFilmstripSelection.validToken("fs-123_2")); assertFalse(NativeFilmstripSelection.validToken("")); assertFalse(NativeFilmstripSelection.validToken("a".repeat(65))); assertFalse(NativeFilmstripSelection.validToken("');alert(1)//")) }
}
