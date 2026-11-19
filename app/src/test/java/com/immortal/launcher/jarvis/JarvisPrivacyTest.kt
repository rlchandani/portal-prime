/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The facts Jarvis's screenshot guard reads: every key is always present, and no screen reads as JSON null. */
class JarvisPrivacyTest {
  private val quiet = JarvisPrivacy.Facts(screen = null, launcherInFront = false, dreaming = false, callActive = false,
      cameraInUse = false, cameraFeedShowing = false)

  @Test fun aThirdPartyAppInFrontReadsAsNoHearthpaneScreen() {
    val json = JarvisPrivacy.json(quiet)
    assertTrue(json.isNull("screen"))
    listOf("launcher_in_front", "dreaming", "call_active", "camera_in_use", "camera_feed_showing").forEach {
      assertTrue(it, json.has(it))
      assertFalse(it, json.getBoolean(it))
    }
  }

  @Test fun everyFactIsReported() {
    val json = JarvisPrivacy.json(JarvisPrivacy.Facts("home", true, true, true, true, true))
    assertEquals("home", json.getString("screen"))
    listOf("launcher_in_front", "dreaming", "call_active", "camera_in_use", "camera_feed_showing").forEach {
      assertTrue(it, json.getBoolean(it))
    }
  }
}
