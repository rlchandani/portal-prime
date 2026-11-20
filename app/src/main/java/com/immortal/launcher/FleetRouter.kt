/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.FleetReply.err
import com.immortal.launcher.FleetReply.json

/**
 * The fleet API's front door. `/remote/…` goes to the phone remote (it does its own pairing or
 * session auth); everything else must pass the bearer gate before any route runs, and then
 * matches one exact path. Pure: the token and the routes are injected.
 */
internal class FleetRouter(
    private val token: () -> String,
    private val remote: FleetHandler,
    routes: Map<String, FleetHandler>,
) {
  private val routes: Map<String, FleetHandler> = HashMap(routes)

  init {
    require(routes.keys.none { it.startsWith(REMOTE_PREFIX) }) { "a fleet route would shadow the remote" }
  }

  /** The exact paths this router answers behind the gate. */
  val paths: Set<String> get() = routes.keys

  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.path.startsWith(REMOTE_PREFIX)) return remote(req)
    if (!FleetAuth.authorized(req.header("authorization"), token())) return json(401, err("unauthorized"))
    val route = routes[req.path] ?: return json(404, err("not_found"))
    return route(req)
  }

  companion object {
    const val REMOTE_PREFIX = "/remote/"
  }
}

/** Every path the fleet API serves (besides `/remote/…`), by owner. */
internal object FleetPaths {
  const val INFO = "/info"
  const val APPS = "/apps"
  const val INSTALL = "/install"
  const val UPDATE = "/update"
  const val DEV = "/dev"
  const val CONFIG = "/config"
  const val CALENDAR = "/calendar"
  const val SCREENSAVER = "/screensaver"
  const val ACTION = "/action"
  const val FS_LIST = "/fs/list"
  const val FS_READ = "/fs/read"
  const val FS_WRITE = "/fs/write"
  const val LOGCAT = "/logcat"
  const val DIAG = "/diag"
  const val PERF = "/perf"
  const val PERF_STATE = "/perf/state"
  const val PERF_FILMSTRIP_RENDER = "/perf/filmstrip-render"
  const val PERF_RECENT_APPS = "/perf/recent-apps"
  const val PERF_WEBAPP_STARTUP = "/perf/webapp-startup"
  const val PERF_TOOLS_DISCOVERY = "/perf/tools-discovery"
  const val PERF_REMOTE_INPUT = "/perf/remote-input"

  /** Served by other components that take the Context and request themselves. */
  val PROBES = setOf(
      "/perf/home-ui", "/perf/app-badges", "/perf/store-work", "/perf/settings-ui", "/perf/webapp",
      "/perf/source-preview", "/perf/photo-upload", "/perf/calling", "/perf/photo-thumbnail",
      "/perf/callmsg", "/perf/source-cache", "/perf/source-photo-preview", "/perf/bridge-write")
}

/** The fleet API's route table: which handler, and which method, answers each path. Pure. */
internal object FleetRouteTable {
  fun build(
      device: FleetDeviceApi,
      installs: FleetInstallApi,
      files: FleetFileApi,
      actions: FleetActionApi,
      perf: FleetPerfApi,
      perfHost: FleetPerf,
      /** Routes served by components that check their own methods: OTA, backups and the probes. */
      delegated: Map<String, FleetHandler>,
  ): Map<String, FleetHandler> {
    val own = mapOf(
        FleetPaths.INFO to get { device.info() },
        FleetPaths.APPS to get { installs.apps() },
        FleetPaths.INSTALL to post(installs::install),
        FleetPaths.UPDATE to post(installs::update),
        FleetPaths.DEV to device::dev,
        FleetPaths.CONFIG to post(device::config),
        FleetPaths.CALENDAR to device::calendar,
        FleetPaths.SCREENSAVER to device::screensaver,
        FleetPaths.ACTION to post(actions::handle),
        FleetPaths.FS_LIST to get(files::list),
        FleetPaths.FS_READ to get(files::read),
        FleetPaths.FS_WRITE to post(files::write),
        FleetPaths.LOGCAT to get(device::logcat),
        FleetPaths.DIAG to get { device.diag() },
        FleetPaths.PERF to perf::capture,
        FleetPaths.PERF_STATE to perf::state,
        FleetPaths.PERF_FILMSTRIP_RENDER to perf::filmstripRender,
        FleetPaths.PERF_RECENT_APPS to perf.gated(perfHost::recentApps),
        FleetPaths.PERF_WEBAPP_STARTUP to perf.gated(perfHost::webAppStartup),
        FleetPaths.PERF_TOOLS_DISCOVERY to perf.gated(perfHost::toolsDiscovery),
        FleetPaths.PERF_REMOTE_INPUT to perf.gated(perfHost::remoteInput),
    )
    val clash = own.keys intersect delegated.keys
    require(clash.isEmpty()) { "routes claimed twice: $clash" }
    return own + delegated
  }

  private fun get(block: (FleetHttpServer.Request) -> FleetHttpServer.Response): FleetHandler =
      { req -> FleetReply.requireMethod("GET", req) { block(req) } }

  private fun post(block: (FleetHttpServer.Request) -> FleetHttpServer.Response): FleetHandler =
      { req -> FleetReply.requireMethod("POST", req) { block(req) } }
}
