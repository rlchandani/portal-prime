/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fleet file routes must never read or overwrite the YouTube sign-in or guest Wi-Fi stores. */
class FleetSecretPathsTest {
  private val root = File(Files.createTempDirectory("app").toFile(), "no_backup").apply { mkdirs() }
  private val store = File(root, "youtube").apply { mkdirs() }
  private val secret = File(store, "accounts.bin").apply { writeText("x") }

  @Test fun theAccountStoreIsBlocked() = assertTrue(FleetSecretPaths.isBlocked(secret))

  @Test fun traversalBackIntoTheStoreIsBlocked() =
      assertTrue(FleetSecretPaths.isBlocked(File(root, "other/../youtube/accounts.bin")))

  @Test fun theFolderItselfIsBlocked() = assertTrue(FleetSecretPaths.isBlocked(store))

  @Test fun aSymlinkPointingIntoTheStoreIsBlocked() {
    val link = File(root.parentFile, "innocent.bin")
    runCatching { Files.createSymbolicLink(link.toPath(), secret.toPath()) }
    if (link.exists()) assertTrue(FleetSecretPaths.isBlocked(link))
  }

  @Test fun theGuestWifiStoreIsBlocked() {
    val guest = File(root, "guest").apply { mkdirs() }
    val wifi = File(guest, "wifi.bin").apply { writeText("x") }
    assertTrue(FleetSecretPaths.isBlocked(wifi))
    assertTrue(FleetSecretPaths.isBlocked(guest))
    assertTrue(FleetSecretPaths.isBlocked(File(root, "youtube/../guest/wifi.bin")))
    assertFalse(FleetSecretPaths.isBlocked(File(root, "guestbook.txt")))
  }

  @Test fun ordinaryFilesStayReadable() {
    assertFalse(FleetSecretPaths.isBlocked(File(root, "youtube-notes.txt")))
    assertFalse(FleetSecretPaths.isBlocked(File(root.parentFile, "youtube/accounts.bin")))
    assertFalse(FleetSecretPaths.isBlocked(File("/sdcard/Pictures/a.png")))
  }
}
