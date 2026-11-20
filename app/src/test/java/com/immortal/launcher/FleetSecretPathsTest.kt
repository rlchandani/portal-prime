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

  // --- /fs/read refuses more than list and write ------------------------------------------

  private val pkg = "com.immortal.launcher.debug"

  private fun readBlocked(path: String) = FleetSecretPaths.isReadBlockedParts(parts(path), parts(path))

  private fun parts(path: String) = path.split('/').filter { it.isNotEmpty() }

  @Test fun theWhosThereStoreIsBlockedForEveryRoute() {
    val whos = File(root, WhosThereStore.DIR).apply { mkdirs() }
    assertTrue(FleetSecretPaths.isBlocked(File(whos, "household.bin")))
    assertTrue(FleetSecretPaths.isBlocked(whos))
  }

  @Test fun readsRefuseEverySecretHoldingPrivateStore() {
    for (sub in listOf("shared_prefs/immortal_household.xml", "shared_prefs/immortal_ha.xml",
        "shared_prefs/remote_pairing.xml", "shared_prefs/fleet_agent.xml", "databases/any.db",
        "files/household_list.json", "no_backup/remote-companion.json", "no_backup/whos_there/household.bin",
        "app_webview/Cookies", "cache/ss_remote/a.jpg", "")) {
      assertTrue(sub, readBlocked("/data/user/0/$pkg/$sub"))
      assertTrue(sub, readBlocked("/data/data/$pkg/$sub"))
      assertTrue(sub, readBlocked("/data/user/10/$pkg/$sub"))
    }
    assertTrue(readBlocked("/data/user_de/0/$pkg/files/x"))
    assertTrue(readBlocked("/data_mirror/data_ce/null/0/$pkg/shared_prefs/a.xml"))
  }

  @Test fun readsRefuseTheFleetManifestOnExternalStorage() {
    assertTrue(readBlocked("/storage/emulated/0/Android/data/$pkg/files/fleet/agent.json"))
    assertTrue(readBlocked("/sdcard/Android/data/$pkg/files/fleet/provision.json"))
    assertFalse(readBlocked("/sdcard/Android/data/$pkg/files/dev/immortal-dev.apk"))
  }

  @Test fun readsRefuseProcSideDoorsButKeepTheFleetDiagnostics() {
    for (door in listOf("fd/3", "fdinfo/3", "map_files/7f00-7f01", "mem", "pagemap", "root/data/x",
        "cwd/shared_prefs/a.xml", "exe", "environ", "task/123/fd/4", "task/123/mem")) {
      assertTrue(door, readBlocked("/proc/self/$door"))
      assertTrue(door, readBlocked("/proc/4242/$door"))
    }
    assertFalse(readBlocked("/proc/self/maps"))
    assertFalse(readBlocked("/proc/self/cmdline"))
    assertFalse(readBlocked("/proc/4242/status"))
    assertFalse(readBlocked("/proc/meminfo"))
  }

  @Test fun readsKeepWhatTheFleetToolsUse() {
    // room-fleet hashes the installed APK; fleetctl pull/cat logs and dev APKs from /sdcard.
    assertFalse(readBlocked("/data/app/$pkg-AbC==/base.apk"))
    assertFalse(readBlocked("/sdcard/some.log"))
    assertFalse(readBlocked("/storage/emulated/0/Pictures/a.png"))
    assertFalse(readBlocked("/system/build.prop"))
    assertFalse(readBlocked("/sdcard/data/user/0/notes.txt"))
  }

  @Test fun readsJudgeTheResolvedTargetNotTheName() {
    // A link on shared storage that resolves into shared_prefs is refused...
    val prefs = File("/data/user/0/$pkg/shared_prefs/immortal_household.xml")
    assertTrue(FleetSecretPaths.isReadBlocked(File("/sdcard/innocent.xml")) { prefs })
    // ...and so is a /proc/self/fd path, even when its target looks harmless.
    assertTrue(FleetSecretPaths.isReadBlocked(File("/proc/self/fd/9")) { File("/sdcard/a.txt") })
    // A failed resolution refuses.
    assertTrue(FleetSecretPaths.isReadBlocked(File("/sdcard/a.txt")) { throw java.io.IOException("x") })
    assertFalse(FleetSecretPaths.isReadBlocked(File("/sdcard/a.txt")) { File("/storage/emulated/0/a.txt") })
  }

  @Test fun readsStillRefuseTheExistingStores() {
    assertTrue(FleetSecretPaths.isReadBlocked(secret))
    assertFalse(FleetSecretPaths.isReadBlocked(File(root.parentFile, "youtube-notes.txt")))
  }

  @Test fun listAndWriteStayOpenForPreferenceRestore() {
    // fleet-restore.sh --prefs lists and pushes shared_prefs; only reads are refused.
    val target = File("/data/user/0/$pkg/shared_prefs/immortal_screensaver.xml")
    assertFalse(FleetSecretPaths.isBlocked(target))
    assertTrue(FleetSecretPaths.isReadBlocked(target))
  }
}
