/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class RemoteFeatureRoutesTest {
  private val route = RemoteFeatureRoutes.Route { _, _, _ -> RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok()) }

  @Test fun registersUnderRemote_andLooksUp() {
    RemoteFeatureRoutes.register("/remote/test-feature-a", route)
    assertSame(route, RemoteFeatureRoutes.lookup("/remote/test-feature-a"))
    assertNull(RemoteFeatureRoutes.lookup("/remote/not-registered"))
  }

  @Test fun rejectsPathsOutsideRemote() {
    assertThrows(IllegalArgumentException::class.java) { RemoteFeatureRoutes.register("/fleet/x", route) }
    assertThrows(IllegalArgumentException::class.java) { RemoteFeatureRoutes.register("/remote/", route) }
  }

  @Test fun aPathHasOneOwner() {
    RemoteFeatureRoutes.register("/remote/test-feature-b", route)
    RemoteFeatureRoutes.register("/remote/test-feature-b", route) // same owner re-registering is fine
    val other = RemoteFeatureRoutes.Route { _, _, _ -> RemoteFeatureRoutes.json(500, RemoteFeatureRoutes.error("x")) }
    assertThrows(IllegalStateException::class.java) { RemoteFeatureRoutes.register("/remote/test-feature-b", other) }
  }
}
