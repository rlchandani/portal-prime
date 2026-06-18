/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class FleetFsTest {
  @Test
  fun entryType_mapsDirFileOther() {
    assertEquals("dir", FleetFs.entryType(isDir = true, isFile = false))
    assertEquals("file", FleetFs.entryType(isDir = false, isFile = true))
    assertEquals("other", FleetFs.entryType(isDir = false, isFile = false)) // e.g. a symlink/socket
  }
}
