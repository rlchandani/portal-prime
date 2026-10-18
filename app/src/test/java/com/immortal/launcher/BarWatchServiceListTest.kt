/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class BarWatchServiceListTest {
  private val own = "public.immortal/public.immortal.BarWatchService"
  private val a = "public.alpha/.Reader"
  private val b = "public.beta/.Reader"
  private fun update(current: String, on: Boolean = true) =
      BarWatchServiceList.updated(current, own, on)

  @Test fun enabledFrontKeepsOrder() {
    assertEquals("$own:$a:$b", update("$own:$a:$b"))
  }
  @Test fun enabledMiddleKeepsOrder() {
    assertEquals("$a:$own:$b", update("$a:$own:$b"))
  }
  @Test fun enabledEndKeepsOrder() {
    assertEquals("$a:$b:$own", update("$a:$b:$own"))
  }
  @Test fun enabledCanonicalKeepsRawColonAndWhitespaceBytes() {
    for (s in listOf(":$own:$a::", "$a:: $b :$own:", "::$a:$own:::$b: \t:"))
      assertEquals(s, update(s))
  }
  @Test fun missingServiceAppendsCanonicalAfterForeignEntries() {
    assertEquals("$a:$b:$own", update(":$a:: \t:$b:"))
    assertEquals(own, update(""))
  }
  @Test fun disablingRemovesOnlyOwnMatches() {
    assertEquals("$a:$b", update("$a:$own:$b:${own.uppercase()}", false))
  }
  @Test fun disablingMissingServicePreservesForeignOrder() {
    assertEquals("$b:$a:$a", update(":$b::$a:$a:", false))
  }
  @Test fun duplicateCanonicalMatchesAreRepaired() {
    assertEquals("$a:$b:$own", update("$own:$a:$own:$b"))
  }
  @Test fun wrongCaseMatchIsRepaired() {
    assertEquals("$a:$b:$own", update("$a:${own.uppercase()}:$b"))
  }
  @Test fun mixedCaseDuplicatesAreRepaired() {
    assertEquals("$b:$a:$own", update("${own.uppercase()}:$b:$own:$a"))
  }
  @Test fun foreignEntriesIncludingDuplicatesAndNearMatchesArePreserved() {
    val foreign = "$own.extra:$a:$a: $own :$b"
    assertEquals("$foreign:$own", update(foreign))
  }
  @Test fun repeatedEnableAndDisableAreIdempotent() {
    for (s in listOf("$own:$a:$b", "$a:$own:$b", ":$own::$a:", "$a", "${own.uppercase()}:$b")) {
      val enabled = update(s)
      assertEquals(enabled, update(update(enabled)))
      val disabled = update(s, false)
      assertEquals(disabled, update(update(disabled, false), false))
    }
  }
}
