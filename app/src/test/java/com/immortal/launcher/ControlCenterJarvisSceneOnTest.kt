/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: voice matched a scene by name under "turn on" but built no call for it, so
 * "turn on movie time" replied "I can't turn on Movie time." instead of running the scene.
 */
class ControlCenterJarvisSceneOnTest {
  private val movie = CcEntity("scene.movie", "scening", mapOf("friendly_name" to "Movie time", "entity_id" to listOf("light.a")))
  private val state = CcUiState(entities = mapOf(movie.id to movie))

  @Test fun turnOnASceneByNameRunsIt() {
    val r = CcHomeCommand.resolve(state, CcHomeRequest("turn on movie time"))
    assertTrue("was: $r", r is CcHomeIntent.Act)
    assertEquals(listOf(CcCalls.run(movie)), (r as CcHomeIntent.Act).items.map { it.call })
  }

  @Test fun turnOffASceneStillCannotBeDone() {
    val r = CcHomeCommand.resolve(state, CcHomeRequest("turn off movie time"))
    assertTrue("was: $r", r !is CcHomeIntent.Act)
  }
}
