/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class WebAppEntryStateTest {
  @Test fun sameEntryRevealsExistingPageWithoutLoading() {
    assertFalse(WebAppEntryState("https://fixture.invalid/music").request("https://fixture.invalid/music"))
  }
  @Test fun changedEntryNavigatesOnceThenRetainsItsDocument() {
    val state = WebAppEntryState("https://fixture.invalid/one")
    assertTrue(state.request("https://fixture.invalid/two"))
    assertFalse(state.request("https://fixture.invalid/two"))
    assertTrue(state.request("https://fixture.invalid/one"))
  }
  @Test fun entryComparisonDoesNotConflateDifferentQueriesOrFragments() {
    val state = WebAppEntryState("https://fixture.invalid/app?a=1")
    assertTrue(state.request("https://fixture.invalid/app?a=2"))
    assertTrue(state.request("https://fixture.invalid/app?a=2#chapter"))
    assertFalse(state.request("https://fixture.invalid/app?a=2#chapter"))
  }
}
