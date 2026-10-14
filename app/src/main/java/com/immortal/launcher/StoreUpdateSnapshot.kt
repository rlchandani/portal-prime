/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Identifies the installed-state read for this catalog and Activity visit. */
internal data class InstalledCatalogRequest(
    val apps: List<CatalogApp>, val resume: Int, val installedEpoch: Int,
)

internal data class InstalledCatalogSnapshot(
    val request: InstalledCatalogRequest, val versions: Map<String, Long>,
) {
  fun versionsFor(current: InstalledCatalogRequest): Map<String, Long>? =
      versions.takeIf { request == current }
}

/** Snapshot-based scans must not share results with a different installed state. */
internal data class CatalogUpdateRequest(
    val apps: List<CatalogApp>, val installedVersions: Map<String, Long>?,
)

internal fun catalogUpdates(
    request: CatalogUpdateRequest,
    ownPackage: String,
    readInstalled: (String) -> Long?,
    readLatest: (CatalogApp) -> Long?,
): List<Pair<String, Long>> {
  val updates = mutableListOf<Pair<String, Long>>()
  for (app in request.apps) {
    if (app.packageName == ownPackage) continue
    // A missing entry in a supplied snapshot means not installed, never "query again".
    val installed = if (request.installedVersions != null)
      request.installedVersions[app.packageName] else readInstalled(app.packageName)
    if (installed == null) continue
    val latest = readLatest(app) ?: continue
    if (latest > installed) updates += app.packageName to latest
  }
  return updates
}
