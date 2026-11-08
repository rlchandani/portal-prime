/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OtaHttpTest {
  private val body = ByteArray(200_000) { (it % 251).toByte() }

  @Test fun copyHashesWhatItWrites() {
    val out = ByteArrayOutputStream()
    val result = OtaHttp.copyBounded(ByteArrayInputStream(body), out, body.size.toLong())
    assertEquals(body.size.toLong(), result.bytes)
    assertEquals(OtaIntegrity.sha256Hex(body), result.sha256)
    assertArrayEquals(body, out.toByteArray())
  }

  @Test fun oneByteOverTheManifestSizeFails() {
    assertThrows(OtaIntegrityException::class.java) {
      OtaHttp.copyBounded(ByteArrayInputStream(body), ByteArrayOutputStream(), body.size - 1L)
    }
  }

  @Test fun shortBodyIsReportedForTheSizeCheck() {
    val result = OtaHttp.copyBounded(ByteArrayInputStream(body, 0, 10), ByteArrayOutputStream(), body.size.toLong())
    assertEquals(10L, result.bytes)
  }
}
