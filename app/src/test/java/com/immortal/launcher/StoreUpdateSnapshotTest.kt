/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class StoreUpdateSnapshotTest {
  private val ownPackage = "com.immortal.launcher"

  @Test fun suppliedSnapshotNeverQueriesPackagesIncludingAbsentEntries() {
    val apps = listOf(app(ownPackage), app("fixture.present"), app("fixture.absent"))
    val latestReads = mutableListOf<String>()
    val updates = catalogUpdates(
        CatalogUpdateRequest(apps, mapOf(ownPackage to 1L, "fixture.present" to 2L)),
        ownPackage,
        { pkg -> throw AssertionError("Unexpected installed-package read: $pkg") },
        { candidate -> latestReads.add(candidate.packageName); 4L },
    )
    assertEquals(listOf("fixture.present" to 4L), updates)
    assertEquals(listOf("fixture.present"), latestReads)
  }

  @Test fun emptySnapshotDoesNoPackageOrLatestVersionReads() {
    val updates = catalogUpdates(
        CatalogUpdateRequest(listOf(app(ownPackage), app("fixture.absent")), emptyMap()),
        ownPackage,
        { throw AssertionError("An empty snapshot must not fall back to PackageManager") },
        { throw AssertionError("Uninstalled apps must not resolve latest versions") },
    )
    assertTrue(updates.isEmpty())
  }

  @Test fun nullSnapshotUsesLegacyReadsAndSkipsSelfMissingEqualNewerAndUnknownLatest() {
    val apps = listOf(ownPackage, "fixture.older", "fixture.equal", "fixture.newer",
        "fixture.unknown", "fixture.missing").map(::app)
    val installed = mapOf("fixture.older" to 1L, "fixture.equal" to 4L,
        "fixture.newer" to 7L, "fixture.unknown" to 2L)
    val latest = mapOf("fixture.older" to 3L, "fixture.equal" to 4L, "fixture.newer" to 6L)
    val packageReads = mutableListOf<String>()
    val latestReads = mutableListOf<String>()
    val updates = catalogUpdates(CatalogUpdateRequest(apps, null), ownPackage,
        { pkg -> packageReads.add(pkg); installed[pkg] },
        { candidate -> latestReads.add(candidate.packageName); latest[candidate.packageName] })
    assertEquals(listOf("fixture.older" to 3L), updates)
    assertEquals(listOf("fixture.older", "fixture.equal", "fixture.newer", "fixture.unknown",
        "fixture.missing"), packageReads)
    assertEquals(listOf("fixture.older", "fixture.equal", "fixture.newer", "fixture.unknown"), latestReads)
  }

  @Test fun differentInstalledVersionsNeverShareAnInFlightUpdateResult() {
    val apps = listOf(app("fixture.app"))
    val old = CatalogUpdateRequest(apps, mapOf("fixture.app" to 1L))
    val current = CatalogUpdateRequest(apps, mapOf("fixture.app" to 2L))
    val delivery = CatalogRequestDelivery<CatalogUpdateRequest, Pair<String, Long>>()
    val oldResults = mutableListOf<List<Pair<String, Long>>>()
    val currentResults = mutableListOf<List<Pair<String, Long>>>()
    assertTrue(delivery.subscribe(old) { oldResults.add(it) }.startLoad)
    assertTrue(delivery.subscribe(current) { currentResults.add(it) }.startLoad)
    val oldUpdates = listOf("fixture.app" to 2L)
    delivery.complete(old).forEach { it(oldUpdates) }
    assertEquals(listOf(oldUpdates), oldResults)
    assertTrue(currentResults.isEmpty())
    delivery.complete(current).forEach { it(emptyList()) }
    assertEquals(listOf(emptyList<Pair<String, Long>>()), currentResults)
  }

  @Test fun equalInstalledMapsShareOneScanRegardlessOfMapOrderOrIdentity() {
    val apps = listOf(app("fixture.first"), app("fixture.second"))
    val first = CatalogUpdateRequest(apps, linkedMapOf("fixture.first" to 1L, "fixture.second" to 2L))
    val equal = CatalogUpdateRequest(apps.toList(), linkedMapOf("fixture.second" to 2L, "fixture.first" to 1L))
    assertNotSame(first.installedVersions, equal.installedVersions)
    val delivery = CatalogRequestDelivery<CatalogUpdateRequest, Pair<String, Long>>()
    val firstResults = mutableListOf<List<Pair<String, Long>>>()
    val equalResults = mutableListOf<List<Pair<String, Long>>>()
    assertTrue(delivery.subscribe(first) { firstResults.add(it) }.startLoad)
    assertFalse(delivery.subscribe(equal) { equalResults.add(it) }.startLoad)
    val updates = listOf("fixture.first" to 3L)
    delivery.complete(equal).forEach { it(updates) }
    assertEquals(listOf(updates), firstResults)
    assertEquals(listOf(updates), equalResults)
  }

  @Test fun legacyAndExplicitEmptySnapshotsDoNotCoalesce() {
    val apps = listOf(app("fixture.app"))
    val delivery = CatalogRequestDelivery<CatalogUpdateRequest, Pair<String, Long>>()
    assertTrue(delivery.subscribe(CatalogUpdateRequest(apps, null)) { }.startLoad)
    assertTrue(delivery.subscribe(CatalogUpdateRequest(apps, emptyMap())) { }.startLoad)
  }

  @Test fun snapshotAcceptsAnEqualCurrentRequestWithoutCopyingVersions() {
    val request = InstalledCatalogRequest(listOf(app("fixture.app")), resume = 2, installedEpoch = 3)
    val versions = mapOf("fixture.app" to 7L)
    val snapshot = InstalledCatalogSnapshot(request, versions)
    val equal = InstalledCatalogRequest(listOf(app("fixture.app")), resume = 2, installedEpoch = 3)
    assertNotSame(request, equal)
    assertSame(versions, snapshot.versionsFor(equal))
  }

  @Test fun snapshotRejectsAChangedCatalogIncludingMetadataChanges() {
    val original = app("fixture.app")
    val request = InstalledCatalogRequest(listOf(original), resume = 2, installedEpoch = 3)
    val snapshot = InstalledCatalogSnapshot(request, mapOf("fixture.app" to 7L))
    assertNull(snapshot.versionsFor(request.copy(apps = listOf(original, app("fixture.added")))))
    assertNull(snapshot.versionsFor(request.copy(apps = listOf(original.copy(versionCode = 8L)))))
  }

  @Test fun snapshotRejectsADifferentResume() {
    val request = InstalledCatalogRequest(listOf(app("fixture.app")), resume = 2, installedEpoch = 3)
    val snapshot = InstalledCatalogSnapshot(request, mapOf("fixture.app" to 7L))
    assertNull(snapshot.versionsFor(request.copy(resume = 3)))
  }

  @Test fun snapshotRejectsADifferentInstallEpoch() {
    val request = InstalledCatalogRequest(listOf(app("fixture.app")), resume = 2, installedEpoch = 3)
    val snapshot = InstalledCatalogSnapshot(request, mapOf("fixture.app" to 7L))
    assertNull(snapshot.versionsFor(request.copy(installedEpoch = 4)))
  }

  @Test fun fortyAppVisitReducesTotalInstalledReadsFromSeventyNineToForty() {
    assertVisitReadReduction(40)
  }

  @Test fun eightyAppVisitReducesTotalInstalledReadsFromOneHundredFiftyNineToEighty() {
    assertVisitReadReduction(80)
  }

  /** Both visits prepare installed UI state; only the legacy scan reads it a second time. */
  private fun assertVisitReadReduction(size: Int) {
    val apps = (0 until size).map { index -> app(if (index == 0) ownPackage else "fixture.app.$index") }
    val deviceVersions = apps.mapIndexedNotNull { index, candidate ->
      if (index != 0 && index % 4 == 0) null else candidate.packageName to 10L
    }.toMap()
    val latestVersions = apps.mapIndexed { index, candidate ->
      candidate.packageName to when (index % 4) { 1 -> 11L; 2 -> 10L; else -> 9L }
    }.toMap()
    var legacyReads = 0
    val legacyReader: (String) -> Long? = { pkg -> legacyReads++; deviceVersions[pkg] }
    val legacyUiVersions = apps.mapNotNull { candidate ->
      legacyReader(candidate.packageName)?.let { candidate.packageName to it }
    }.toMap()
    assertEquals(size, legacyReads)
    val legacyLatestReads = mutableListOf<String>()
    val legacy = catalogUpdates(CatalogUpdateRequest(apps, null), ownPackage, legacyReader,
        { candidate -> legacyLatestReads.add(candidate.packageName); latestVersions[candidate.packageName] })

    var snapshotReads = 0
    val snapshotReader: (String) -> Long? = { pkg -> snapshotReads++; deviceVersions[pkg] }
    val snapshotUiVersions = apps.mapNotNull { candidate ->
      snapshotReader(candidate.packageName)?.let { candidate.packageName to it }
    }.toMap()
    val request = InstalledCatalogRequest(apps, resume = 1, installedEpoch = 0)
    val snapshot = InstalledCatalogSnapshot(request, snapshotUiVersions)
    val snapshotLatestReads = mutableListOf<String>()
    val reused = catalogUpdates(CatalogUpdateRequest(apps, snapshot.versionsFor(request)), ownPackage,
        snapshotReader,
        { candidate -> snapshotLatestReads.add(candidate.packageName); latestVersions[candidate.packageName] })

    val expected = (1 until size step 4).map { "fixture.app.$it" to 11L }
    assertEquals(expected, legacy)
    assertEquals(expected, reused)
    assertEquals(legacyUiVersions, snapshotUiVersions)
    assertEquals(legacyLatestReads, snapshotLatestReads)
    assertEquals("UI plus legacy update scan reads", 2 * size - 1, legacyReads)
    assertEquals("UI plus snapshot update scan reads", size, snapshotReads)
  }

  private fun app(packageName: String) = CatalogApp(
      name = packageName, packageName = packageName, source = "url", fdroidId = null,
      apkUrl = null, versionCode = null, description = "Fixture app", category = "Tests",
  )
}
