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

/** Fleet file routes must never list, read or overwrite the app's private data or its secret stores. */
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

  // --- the private-data rule (list, read and write) ------------------------------------------

  private val pkg = "com.immortal.launcher.debug"

  private fun readBlocked(path: String) = FleetSecretPaths.isPrivateParts(parts(path), parts(path))

  private fun parts(path: String) = path.split('/').filter { it.isNotEmpty() }

  @Test fun theWhosThereStoreIsBlockedForEveryRoute() {
    val whos = File(root, WhosThereStore.DIR).apply { mkdirs() }
    assertTrue(FleetSecretPaths.isBlocked(File(whos, "household.bin")))
    assertTrue(FleetSecretPaths.isBlocked(whos))
  }

  @Test fun theSealedHouseholdKeyIsBlockedForEveryRoute() {
    val dir = File(root, HouseholdKey.DIR).apply { mkdirs() }
    assertTrue(FleetSecretPaths.isBlocked(File(dir, "key.bin")))
    assertTrue(FleetSecretPaths.isBlocked(dir))
  }

  @Test fun readsRefuseEverySecretHoldingPrivateStore() {
    for (sub in listOf("shared_prefs/immortal_household.xml", "shared_prefs/immortal_ha.xml",
        "shared_prefs/remote_pairing.xml", "shared_prefs/fleet_agent.xml", "databases/any.db",
        "files/household_list.json", "no_backup/remote-companion.json", "no_backup/whos_there/household.bin",
        "no_backup/household/key.bin", "no_backup/memory/household.bin",
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
    assertTrue(FleetSecretPaths.isPrivate(File("/sdcard/innocent.xml")) { prefs })
    // ...and so is a /proc/self/fd path, even when its target looks harmless.
    assertTrue(FleetSecretPaths.isPrivate(File("/proc/self/fd/9")) { File("/sdcard/a.txt") })
    // A failed resolution refuses.
    assertTrue(FleetSecretPaths.isPrivate(File("/sdcard/a.txt")) { throw java.io.IOException("x") })
    assertFalse(FleetSecretPaths.isPrivate(File("/sdcard/a.txt")) { File("/storage/emulated/0/a.txt") })
  }

  @Test fun readsStillRefuseTheExistingStores() {
    assertTrue(FleetSecretPaths.isPrivate(secret))
    assertFalse(FleetSecretPaths.isPrivate(File(root.parentFile, "youtube-notes.txt")))
  }

  // --- /fs/write and /fs/list (audit finding 13) -----------------------------------------

  private val prefs = "/data/user/0/$pkg/shared_prefs/immortal_household.xml"

  /** As the route sees it: [requested] as typed, canonicalized to [resolvesTo]. */
  private fun refused(requested: String, resolvesTo: String) =
      FleetSecretPaths.isPrivate(File(requested)) { File(resolvesTo) }

  @Test fun writesRefuseEveryPrivateStoreUnderEveryAlias() {
    for (sub in listOf("shared_prefs/immortal_household.xml", "shared_prefs/remote_pairing.xml",
        "shared_prefs/new.xml", "shared_prefs", "databases/any.db", "files/household_list.json",
        "no_backup/remote-companion.json", "app_webview/Cookies", "cache/a.jpg")) {
      val canonical = "/data/user/0/$pkg/$sub"
      assertTrue(sub, refused("/data/data/$pkg/$sub", canonical))
      assertTrue(sub, refused(canonical, canonical))
      assertTrue(sub, refused("/proc/self/root/data/data/$pkg/$sub", canonical))
      assertTrue(sub, refused("/proc/self/cwd/$sub", canonical))
      assertTrue(sub, refused("/data/user_de/0/$pkg/$sub", "/data/user_de/0/$pkg/$sub"))
      assertTrue(sub, refused("/data_mirror/data_ce/null/0/$pkg/$sub", "/data_mirror/data_ce/null/0/$pkg/$sub"))
    }
  }

  @Test fun writesJudgeBothTheRequestedAndTheResolvedPath() {
    // `..` out of shared storage and a link that resolves into shared_prefs: the resolved path.
    assertTrue(refused("/sdcard/../../data/data/$pkg/shared_prefs/immortal_household.xml", prefs))
    assertTrue(refused("/sdcard/innocent.xml", prefs))
    // The requested name alone is enough, even if resolution claims somewhere harmless.
    assertTrue(refused("/data/data/$pkg/shared_prefs/immortal_household.xml", "/sdcard/a.xml"))
    assertTrue(refused("/proc/self/root/sdcard/a.xml", "/sdcard/a.xml"))
    // The fleet manifest (it carries the fleet token) can't be replaced either.
    assertTrue(refused("/sdcard/Android/data/$pkg/files/fleet/agent.json",
        "/storage/emulated/0/Android/data/$pkg/files/fleet/agent.json"))
    // A path that can't be resolved is refused.
    assertTrue(FleetSecretPaths.isPrivate(File("/sdcard/a.xml")) { throw java.io.IOException("x") })
  }

  @Test fun writesRefuseRealTraversalAndSymlinksIntoAPrivateTree() {
    // On the JVM a temp tree stands in for the data dir; the rule runs on the real canonical path.
    val data = Files.createTempDirectory("data").toFile().canonicalFile
    val storage = Files.createTempDirectory("sdcard").toFile().canonicalFile
    val sharedPrefs = File(data, "data/$pkg/shared_prefs").apply { mkdirs() }
    val link = File(storage, "prefs-link")
    runCatching { Files.createSymbolicLink(link.toPath(), sharedPrefs.toPath()) }
    // Real canonicalization, then the temp roots renamed to their device names.
    val asDevice: (File) -> File = { f ->
      val path = f.canonicalPath
      File(when {
        path.startsWith(data.path) -> "/data" + path.removePrefix(data.path)
        path.startsWith(storage.path) -> "/sdcard" + path.removePrefix(storage.path)
        else -> path
      })
    }
    if (link.exists()) assertTrue(FleetSecretPaths.isPrivate(File(link, "immortal_household.xml"), asDevice))
    assertTrue(FleetSecretPaths.isPrivate(File(storage, "../${data.name}/data/$pkg/shared_prefs/a.xml"), asDevice))
    assertFalse(FleetSecretPaths.isPrivate(File(storage, "frame.jpg"), asDevice))
  }

  @Test fun writesKeepWhatTheFleetToolsPush() {
    // fleetctl dev update (dev APK), fleetctl push (frames, files on shared storage), test files.
    for (path in listOf("/sdcard/Android/data/$pkg/files/dev/immortal-dev.apk",
        "/sdcard/Android/data/com.immortal.launcher/files/frame.jpg", "/sdcard/Download/a.txt",
        "/storage/emulated/0/Music/song.flac", "/sdcard/data/user/0/notes.txt", "/public/dummy.apk")) {
      assertFalse(path, refused(path, path.replaceFirst("/sdcard/", "/storage/emulated/0/")))
    }
  }

  @Test fun listingsRefuseThePrivateTreeButKeepTheInstalledApk() {
    // room-fleet lists the installed APK's directory for its mtime.
    assertFalse(refused("/data/app/$pkg-AbC==", "/data/app/$pkg-AbC=="))
    assertFalse(refused("/sdcard", "/storage/emulated/0"))
    assertTrue(refused("/data/data/$pkg/shared_prefs", "/data/user/0/$pkg/shared_prefs"))
    assertTrue(refused("/data/data/$pkg", "/data/user/0/$pkg"))
  }
}
