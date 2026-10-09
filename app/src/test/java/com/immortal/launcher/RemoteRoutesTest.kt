/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure Authorization-header parsing behind the remote's session auth. */
class RemoteRoutesTest {

  @Test
  fun bearer_extractsToken() {
    assertEquals("abc123", RemoteRoutes.bearer("Bearer abc123"))
    assertEquals("abc123", RemoteRoutes.bearer("  Bearer abc123  "))
  }

  @Test
  fun bearer_isSchemeCaseInsensitive() {
    assertEquals("tok", RemoteRoutes.bearer("bearer tok"))
    assertEquals("tok", RemoteRoutes.bearer("BEARER tok"))
  }

  @Test
  fun bearer_rejectsMalformed() {
    assertNull(RemoteRoutes.bearer(null))
    assertNull(RemoteRoutes.bearer("abc123")) // no scheme
    assertNull(RemoteRoutes.bearer("Basic abc123")) // wrong scheme
    assertNull(RemoteRoutes.bearer("Bearer ")) // blank token
    assertNull(RemoteRoutes.bearer("Bearer    "))
  }

  @Test
  fun clampWaitMs_boundsPresetWaits() {
    assertEquals(300L, RemoteRoutes.clampWaitMs(300L))
    assertEquals(0L, RemoteRoutes.clampWaitMs(-5L))
    assertEquals(10_000L, RemoteRoutes.clampWaitMs(999_999L))
  }

  @Test fun volumeLevelAcceptsOnlyIntegersWithinTheExactStreamRange() {
    assertEquals(0, RemoteRoutes.validatedVolumeLevel(0, 15))
    assertEquals(15, RemoteRoutes.validatedVolumeLevel(15, 15))
    assertEquals(7, RemoteRoutes.validatedVolumeLevel(7L, 15))
    assertEquals(0, RemoteRoutes.validatedVolumeLevel(0, 0))
    for (value in listOf(null, true, "7", 0.5, 7.0, -1, 16, Long.MAX_VALUE, 4_294_967_296L)) {
      assertNull("value=$value", RemoteRoutes.validatedVolumeLevel(value, 15))
    }
    assertNull(RemoteRoutes.validatedVolumeLevel(0, -1))
  }
}
