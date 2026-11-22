/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HaResultTest {
  private fun of(code: Int, body: String?) = HaResult.of(Http.Outcome(code, body)) { it.toIntOrNull() }

  @Test fun `classifies answers so the screen can word them`() {
    assertEquals(HaResult.Ok(5), of(200, "5"))
    assertEquals(HaResult.Failed(200), of(200, "not a number"))
    assertEquals(HaResult.Unauthorized, of(401, null))
    assertEquals(HaResult.Unauthorized, of(403, null))
    assertEquals(HaResult.Unreachable, of(Http.NO_RESPONSE, null))
    assertEquals(HaResult.Failed(500), of(500, null))
  }

  @Test fun `failures re-type, a value does not`() {
    val failed: HaResult<String> = HaResult.Unreachable.cast()
    assertEquals(HaResult.Unreachable, failed)
    try { HaResult.Ok(1).cast<String>(); throw AssertionError("expected failure") } catch (_: IllegalStateException) {}
  }
}
