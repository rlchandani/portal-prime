/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** procfs files report a nominal size, so they stream to EOF with no declared length (Linux only). */
class FleetFileReadCoverageTest {
  @Test fun procfsFileStreamsWithoutADeclaredLength() {
    val proc = File("/proc/self/status")
    assumeTrue("procfs is only present on Linux", proc.canRead())
    val response = FleetFileRead.response(proc)
    try {
      assertEquals(-1L, response.streamLength)
      assertEquals("attachment; filename=\"status\"; filename*=UTF-8''status", response.extraHeaders["Content-Disposition"])
      val out = ByteArrayOutputStream()
      response.streamWriter!!(out)
      assertEquals(true, out.size() > 0)
    } finally {
      response.close()
    }
  }
}
