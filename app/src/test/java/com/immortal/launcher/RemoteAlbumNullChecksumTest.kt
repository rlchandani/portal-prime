/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteAlbumNullChecksumTest {
  @Test
  fun nullLargerChecksumKeepsUsableSmallerDerivative() {
    assertEquals(
        "good",
        RemoteAlbum.pickBestDerivative(
            JSONObject("""{"1024":{"checksum":"good"},"2048":{"checksum":null}}"""),
            1920, 1080))
  }

  @Test
  fun onlyNullChecksumsOfferNoDerivative() {
    assertNull(
        RemoteAlbum.pickBestDerivative(
            JSONObject("""{"1024":{"checksum":null},"2048":{"checksum":null}}"""),
            1920, 1080))
  }

  @Test
  fun literalNullStringRetainsExistingChecksumSemantics() {
    assertEquals(
        "null",
        RemoteAlbum.pickBestDerivative(
            JSONObject("""{"2048":{"checksum":"null"}}"""), 1920, 1080))
  }

  @Test
  fun absentLargerChecksumKeepsUsableSmallerDerivative() {
    assertEquals(
        "good",
        RemoteAlbum.pickBestDerivative(
            JSONObject("""{"1024":{"checksum":"good"},"2048":{}}"""), 1920, 1080))
  }

  @Test
  fun normalCoveringDerivativeSelectionIsPreserved() {
    assertEquals(
        "large",
        RemoteAlbum.pickBestDerivative(
            JSONObject("""{"1024":{"checksum":"small"},"2048":{"checksum":"large"}}"""),
            1920, 1080))
  }
}
