/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context

/**
 * The Fleet Agent's HTTP API, as served by [FleetHttpServer]: the Android adapter. It wires the
 * pure route classes ([FleetRouter], [FleetDeviceApi], [FleetInstallApi], [FleetFileApi],
 * [FleetActionApi], [FleetPerfApi]) to the app through [FleetAndroidHost], and adds the routes
 * served by components that take the Context themselves (OTA, sealed backups, the phone remote
 * and the performance probes). Routing, the bearer gate, validation, path rules and response
 * shaping all live in the pure classes and run in JVM tests.
 *
 * Every request must carry `Authorization: Bearer <token>` (the per-device token
 * from [FleetConfig]); anything else gets 401 before any work happens. This is a
 * pure CONSUMER of existing APIs — it adds no install/catalog logic of its own,
 * so the App Store and the fleet always install apps the exact same way.
 */
class FleetRoutes(private val context: Context) {
  private val catalog = FleetCatalog()
  private val host = FleetAndroidHost(context)

  /** The phone-remote surface (`/remote/…`), which does its own pairing/session auth. */
  private val remote = RemoteRoutes(context)

  /** Sealed settings backups (`/backup/…`): enrolment, export and import. */
  private val backup = FleetBackupRoutes(context)

  private val router = FleetRouter(
      token = host::token,
      remote = remote::handle,
      routes = FleetRouteTable.build(
          device = FleetDeviceApi(host, host, host, catalog),
          installs = FleetInstallApi(host, host, JvmFleetFiles, catalog),
          files = FleetFileApi(JvmFleetFiles),
          actions = FleetActionApi(host),
          perf = FleetPerfApi(host, host),
          perfHost = host,
          delegated = delegatedRoutes()))

  /** Load (and later refresh) the catalog snapshot used by /info, /install, /update. */
  fun refreshCatalog() {
    StoreCatalog.loadCatalog(context) { catalog.apps = it }
  }

  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response = router.handle(req)

  /** Each probe object is touched only when its route is called, as before (lambdas, not bound references). */
  private fun delegatedRoutes(): Map<String, FleetHandler> {
    val probes: Map<String, (Context, FleetHttpServer.Request) -> FleetHttpServer.Response> = mapOf(
        "/perf/home-ui" to { c, req -> HomeUiDiagnostics.handle(c, req) },
        "/perf/app-badges" to { c, req -> AppBadgeProbe.handle(c, req) },
        "/perf/store-work" to { _, req -> StoreLoadingDiagnostics.handle(req) },
        "/perf/settings-ui" to { c, req -> SettingsUiDiagnostics.handle(c, req) },
        "/perf/webapp" to { c, req -> WebAppProbe.handle(c, req) },
        "/perf/source-preview" to { c, req -> SourcePreviewProbe.handle(c, req) },
        "/perf/photo-upload" to { c, req -> PhotoUploadProbe.handle(c, req) },
        "/perf/calling" to { c, req -> CallPerfProbe.handle(c, req) },
        "/perf/photo-thumbnail" to { c, req -> PhotoThumbnailProbe.handle(c, req) },
        "/perf/callmsg" to { c, req -> CallMessageProbe.handle(c, req) },
        "/perf/source-cache" to { c, req -> SourceCacheProbe.handle(c, req) },
        "/perf/source-photo-preview" to { c, req -> SourcePhotoPreviewProbe.handle(c, req) },
        "/perf/bridge-write" to { c, req -> BridgeWriteProbe.handle(c, req) })
    check(probes.keys == FleetPaths.PROBES) { "probe table out of sync" }
    val out = HashMap<String, FleetHandler>()
    probes.forEach { (path, probe) -> out[path] = { req -> probe(context, req) } }
    out[OtaRoutes.FLEET_PATH] = { req -> OtaRoutes.fleet(context, req) }
    FleetBackupRoutes.PATHS.forEach { out[it] = backup::handle }
    return out
  }
}
